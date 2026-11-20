package com.delta.admin.workbench;

import com.delta.admin.platform.PlatformRemote;
import com.delta.admin.platform.PlatformService;
import com.delta.admin.MySqlTestSupport;
import com.delta.common.config.SecurityConfig;
import com.delta.common.security.filter.JwtAuthenticationFilter;
import com.delta.common.security.handler.AccessDeniedHandlerImpl;
import com.delta.common.security.handler.AuthenticationEntryPointImpl;
import com.delta.common.security.service.StaffPermissions;
import com.delta.common.security.service.TokenService;
import com.delta.message.service.impl.SystemNotificationServiceImpl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = WorkbenchIntegrationTest.Config.class, properties = {
        "spring.profiles.active=test", "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
        "spring.sql.init.mode=never"
})
@AutoConfigureMockMvc
@Transactional
class WorkbenchIntegrationTest {
    @Configuration
    @EnableAutoConfiguration(excludeName = "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @MapperScan("com.delta.message.mapper")
    @Import({WorkbenchController.class, WorkbenchService.class, SystemNotificationServiceImpl.class,
            SecurityConfig.class, JwtAuthenticationFilter.class, AuthenticationEntryPointImpl.class, AccessDeniedHandlerImpl.class})
    static class Config {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        boolean mysql = MySqlTestSupport.enabled("WORKBENCH_TEST_MYSQL");
        registry.add("spring.datasource.url", () -> mysql ? MySqlTestSupport.jdbcUrl("workbench_qa") : "jdbc:h2:mem:workbench;MODE=MySQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> mysql ? MySqlTestSupport.username() : "sa");
        registry.add("spring.datasource.password", () -> mysql ? MySqlTestSupport.password() : "");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkbenchService service;
    @Autowired SystemNotificationServiceImpl notices;
    @Autowired MockMvc mvc;
    @MockBean TokenService tokens;
    @MockBean StaffPermissions permissions;
    @MockBean PlatformService platform;
    @MockBean PlatformRemote remote;
    @MockBean com.delta.system.service.SysConfigService config;
    @MockBean com.delta.pay.service.PaymentService payments;

    @BeforeEach void fixtures() {
        if (!"1".equals(System.getenv("WORKBENCH_TEST_MYSQL"))) {
            new ResourceDatabasePopulator(new ClassPathResource("workbench-fixtures.sql"),
                    new ClassPathResource("db/workbench.sql")).execute(jdbc.getDataSource());
        }
        when(permissions.forStaff(91L, "ADMIN")).thenReturn(List.of("*", "order.refund"));
        when(permissions.forStaff(10L, "CS")).thenReturn(List.of("order.manage", "order.read", "chat.manage"));
        when(permissions.forStaff(11L, "CS")).thenReturn(List.of("order.manage", "order.read"));
        when(permissions.forStaff(12L, "CS")).thenReturn(List.of("user.read"));
        when(platform.featureEnabled(eq("identity_verification"), anyString())).thenReturn(true);
        when(remote.project()).thenReturn(JsonNodeFactory.instance.objectNode().put("maintenanceUntil", "2099-01-01T00:00:00Z"));
    }

    @Test void readingDoesNotCompleteTaskOrAffectOtherStaffAndStateChangesReopenUnread() {
        order(1, 100, null, "PAID");
        var cs = scope("CS", 10);
        var other = scope("CS", 11);
        var item = service.tasks(cs, "ASSIGN", WorkbenchService.Filter.ALL, 1, 5).tasks().get(0);
        service.read(cs, List.of(ref(item)), true);
        service.read(cs, List.of(ref(item)), true);
        assertThat(service.summary(cs).pending()).isEqualTo(1);
        assertThat(service.summary(cs).unread()).isZero();
        assertThat(service.summary(other).unread()).isEqualTo(1);
        assertThat(service.tasks(cs, "ASSIGN", WorkbenchService.Filter.ALL, 1, 5).tasks().get(0).acknowledged()).isTrue();

        jdbc.update("UPDATE `order` SET updated_at=TIMESTAMPADD(MINUTE,-45,CURRENT_TIMESTAMP) WHERE id=1");
        service.read(cs, List.of(ref(item)), false);
        assertThat(service.summary(cs).unread()).isEqualTo(1);
        assertThat(service.summary(cs).urgent()).isEqualTo(1);
        service.readAll(cs);
        assertThat(service.summary(cs).unread()).isZero();
        jdbc.update("UPDATE `order` SET status='ASSIGNED',player_id=70,assign_time=CURRENT_TIMESTAMP WHERE id=1");
        assertThat(service.summary(cs).pending()).isZero();
        assertThat(service.summary(cs).handledToday()).isEqualTo(1);
    }

    @Test void ownOrdersOnlyAndGuestsCannotUseWorkbench() throws Exception {
        order(1, 100, null, "PENDING_PAYMENT");
        order(2, 101, null, "PENDING_PAYMENT");
        order(3, 100, null, "COMPLETED");
        var other = scope("USER", 101);
        var item = service.tasks(other, "PAY", WorkbenchService.Filter.ALL, 1, 5).tasks().get(0);
        service.read(scope("USER", 100), List.of(ref(item)), false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workbench_receipt", Long.class)).isZero();
        assertThat(service.summary(scope("USER", 100)).pending()).isEqualTo(2);
        mvc.perform(get("/workbench/tasks").with(auth("GUEST", 100))).andExpect(status().isForbidden());
        mvc.perform(get("/workbench/tasks").with(auth("USER", 100)).param("kind", "PAY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tasks[0].id").value("1"))
                .andExpect(jsonPath("$.data.tasks.length()").value(1));
        mvc.perform(get("/workbench/summary").with(auth("CS", 12))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(0));
    }

    @Test void adminUnionSupportsVirtualTasksAndPaging() {
        when(remote.pendingReleaseVersion()).thenReturn("1.6.0");
        order(4, 103, null, "PAID");
        var admin = scope("ADMIN", 91);
        assertThat(service.summary(admin).pending()).isEqualTo(2);
        assertThat(service.tasks(admin, "", WorkbenchService.Filter.ALL, 1, 1).hasMore()).isTrue();
        assertThat(service.tasks(admin, "UPDATE", WorkbenchService.Filter.ALL, 1, 1).tasks().get(0).reference()).isEqualTo("1.6.0");
        service.readAll(admin);
        assertThat(service.summary(admin).unread()).isZero();
        assertThat(service.summary(admin).pending()).isEqualTo(2);
        assertThat(service.summary(scope("PLAYER", 70)).pending()).isZero();
    }

    @Test void broadcastReceiptsArePrivateAndDoNotModifyTheNotification() {
        notices.send("CS", 0L, "系统通知", "测试广播", "TEST", null);
        notices.send("CS", 10L, "专属通知", "测试收件人", "TEST", null);
        var broadcast = notices.inbox("CS", 11L, 1, 20, "").getRecords().get(0);
        assertThat(notices.unreadCount("CS", 10L)).isEqualTo(2);
        assertThat(notices.unreadCount("CS", 11L)).isEqualTo(1);
        assertThat(notices.markRead("CS", 11L, broadcast.getId())).isTrue();
        assertThat(notices.inbox("CS", 11L, 1, 20, "").getRecords().get(0).getIsRead()).isEqualTo(1);
        assertThat(notices.unreadCount("CS", 11L)).isZero();
        assertThat(notices.unreadCount("CS", 10L)).isEqualTo(2);
        notices.markAllRead("CS", 10L);
        notices.markAllRead("CS", 10L);
        assertThat(notices.unreadCount("CS", 10L)).isZero();
        assertThat(notices.unreadCount("CS", 12L)).isEqualTo(1);
        assertThat(notices.getById(broadcast.getId()).getIsRead()).isZero();
    }

    @Test void replyClearsChatTaskWhileReadOnlyClearsChatBadge() {
        jdbc.update("INSERT INTO chat_session(id,id1,id2,status) VALUES(1,1000000100,3000000010,'ACTIVE')");
        jdbc.update("INSERT INTO chat_message(id,session_id,sender_type,is_read,content,created_at) "
                + "VALUES(1,1,'USER',0,'qa',TIMESTAMPADD(MINUTE,-20,CURRENT_TIMESTAMP))");
        var cs = scope("CS", 10);
        assertThat(service.summary(cs).chatUnread()).isEqualTo(1);
        assertThat(service.tasks(cs, "CHAT", WorkbenchService.Filter.ALL, 1, 5).tasks()).hasSize(1);
        assertThat(service.summary(scope("CS", 11)).pending()).isZero();
        jdbc.update("UPDATE chat_message SET is_read=1 WHERE id=1");
        assertThat(service.summary(cs).chatUnread()).isZero();
        assertThat(service.summary(cs).pending()).isEqualTo(1);
        jdbc.update("INSERT INTO chat_message(id,session_id,sender_type,is_read,content,created_at) "
                + "VALUES(2,1,'CS',0,'qa reply',CURRENT_TIMESTAMP)");
        assertThat(service.summary(cs).pending()).isZero();
    }

    @Test void manualReviewIsPendingAndChangesPhaseWithoutLosingTheTask() {
        jdbc.update("INSERT INTO player_homepage_revision(id,player_id,payload,status,created_at) "
                + "VALUES('review-1',70,'{}','PENDING_SYSTEM',CURRENT_TIMESTAMP)");
        var admin = scope("ADMIN", 91);
        var item = service.tasks(admin, "MATERIAL", WorkbenchService.Filter.ALL, 1, 5).tasks().get(0);
        service.read(admin, List.of(ref(item)), false);
        assertThat(service.summary(admin).unread()).isZero();
        jdbc.update("UPDATE player_homepage_revision SET status='PENDING_REVIEW' WHERE id='review-1'");
        assertThat(service.summary(admin).unread()).isEqualTo(1);
        jdbc.update("UPDATE player_homepage_revision SET status='APPROVED',reviewed_at=CURRENT_TIMESTAMP WHERE id='review-1'");
        assertThat(service.summary(admin).pending()).isZero();
    }

    @Test void newTeammateSeesAnInvitationEvenWhenServiceHasStarted() {
        order(40, 100, 70L, "IN_PROGRESS");
        jdbc.update("INSERT INTO order_player(id,order_id,player_id,role,status,created_at,invite_deadline) "
                + "VALUES(81,40,71,'TEAMMATE','INVITED',CURRENT_TIMESTAMP,TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP))");
        var invitee = scope("PLAYER", 71);
        assertThat(service.tasks(invitee, "INVITE", WorkbenchService.Filter.ALL, 1, 5).tasks()).hasSize(1);
        assertThat(service.tasks(scope("PLAYER", 72), "INVITE", WorkbenchService.Filter.ALL, 1, 5).tasks()).isEmpty();
        jdbc.update("UPDATE \u0060order\u0060 SET status='COMPLETED' WHERE id=40");
        assertThat(service.tasks(invitee, "INVITE", WorkbenchService.Filter.ALL, 1, 5).tasks()).isEmpty();
    }

    @Test void depositTaskUsesExistingPaymentRulesAndDisappearsAfterTopUp() {
        jdbc.update("INSERT INTO `user`(id,openid) VALUES(44,'fixture')");
        jdbc.update("INSERT INTO player(id,openid,status) VALUES(70,'fixture','ACTIVE')");
        jdbc.update("INSERT INTO player_wallet(player_id,balance) VALUES(70,20.00)");
        when(config.getConfigValue(eq("player.deposit_mode"), anyString())).thenReturn("BEFORE_WITHDRAW");
        when(payments.playerDepositRequiredAmount(44L)).thenReturn(new java.math.BigDecimal("100.00"));
        when(payments.playerDepositPaidAmount(44L)).thenReturn(new java.math.BigDecimal("60.00"));
        var player = scope("PLAYER", 70);
        assertThat(service.tasks(player, "DEPOSIT", WorkbenchService.Filter.ALL, 1, 5).tasks())
                .singleElement().extracting(WorkbenchService.Task::reference).isEqualTo("需补缴 ¥40");
        when(payments.playerDepositPaidAmount(44L)).thenReturn(new java.math.BigDecimal("100.00"));
        assertThat(service.summary(scope("PLAYER", 70)).pending()).isZero();
    }

    private WorkbenchService.Scope scope(String role, long id) { return service.scope(role, id, "test.example"); }
    private WorkbenchService.TaskRef ref(WorkbenchService.Task task) {
        return new WorkbenchService.TaskRef(task.kind(), task.id(), task.version());
    }
    private RequestPostProcessor auth(String type, long id) {
        return authentication(new UsernamePasswordAuthenticationToken(Map.of("userId", id, "userType", type),
                null, List.of(new SimpleGrantedAuthority("ROLE_" + type))));
    }
    private void order(long id, long user, Long player, String state) {
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,player_id,product_id,product_name,amount,game_account,status,created_at,updated_at) "
                + "VALUES (?,?,?, ?,1,'测试服务',1.00,'test',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, "QA-" + id, user, player, state);
    }
}
