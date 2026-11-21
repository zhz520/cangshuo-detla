package com.delta.admin;

import com.delta.admin.config.StaffPermissionConfig;
import com.delta.admin.controller.AdminOrderController;
import com.delta.workforce.controller.StaffOrderAssignmentController;
import com.delta.workforce.controller.StaffOrderTeamController;
import com.delta.common.security.service.StaffPermissions;
import com.delta.cs.controller.CsOrderController;
import com.delta.pay.refund.OrderRefundWorkflowService;
import com.delta.order.service.OrderViewService;
import com.delta.player.service.OrderAssignmentCandidateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = OrderAssignmentCandidatesTest.Config.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:order-assign-candidates;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource", "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@Sql(scripts = "/reward-schema.sql", config = @SqlConfig(encoding = "UTF-8"))
class OrderAssignmentCandidatesTest {
    @Configuration
    @Import({RewardGrabIntegrationTest.Config.class, StaffPermissionConfig.class, StaffOrderAssignmentController.class,
            StaffOrderTeamController.class, OrderAssignmentCandidateService.class,
            AdminOrderController.class, CsOrderController.class, OrderViewService.class})
    static class Config {}

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean(name = "staffPermissions") StaffPermissions staffPermissions;
    @MockBean OrderRefundWorkflowService refunds;
    @MockBean com.delta.product.service.ProductService products;
    @MockBean com.delta.common.security.service.TokenService tokens;
    @MockBean com.delta.common.security.service.VerificationService verification;
    @MockBean com.delta.common.security.service.RateLimitService rateLimits;
    @MockBean com.delta.common.redis.service.RedisService redis;
    @MockBean org.springframework.data.redis.core.StringRedisTemplate strings;
    @MockBean com.delta.common.chat.service.ChatSessionService chats;
    @MockBean com.delta.order.service.PlayerReplaceRequestService replacements;

    @BeforeEach
    void setup() {
        when(staffPermissions.allowed("order.manage")).thenReturn(true);
        jdbc.update("UPDATE `order` SET requested_player_level_id=2,player_level_name='下单时等级',cs_id=81 WHERE id=200");
        jdbc.update("UPDATE player_level SET name='当前等级名',status=0 WHERE id=2");
        player(3, "测试匹配", 2, "ACTIVE", 1, 0);
        player(4, "测试更高", 3, "ACTIVE", 1, 0);
        player(5, "测试离线", 2, "ACTIVE", 0, 0);
        player(6, "测试冻结", 2, "FROZEN", 1, 0);
        player(7, "测试删除", 2, "ACTIVE", 1, 1);
        jdbc.update("INSERT INTO player_level(id,name,sort_order,status) VALUES(3,'当前等级名',99,1)");
    }

    @Test
    void exactLevelAndAvailabilityAreFilteredBeforePagination() throws Exception {
        String path = "/admin/order/200/assign/candidates";
        mvc.perform(get(path).param("pageSize", "1").with(role("ADMIN", 9)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requiredLevelId").value(2))
                .andExpect(jsonPath("$.data.requiredLevelName").value("下单时等级"))
                .andExpect(jsonPath("$.data.players.total").value(2))
                .andExpect(jsonPath("$.data.players.records[0].id").value(2))
                .andExpect(jsonPath("$.data.players.records[0].levelName").value("当前等级名"))
                .andExpect(jsonPath("$.data.players.records[0].realName").doesNotExist());
        mvc.perform(get(path).param("pageSize", "1").param("pageNum", "2").with(role("ADMIN", 9)))
                .andExpect(jsonPath("$.data.players.records[0].id").value(3));
        mvc.perform(get(path).param("keyword", "更高").with(role("ADMIN", 9)))
                .andExpect(jsonPath("$.data.players.total").value(0));

        mvc.perform(get("/admin/order/201/assign/candidates").with(role("ADMIN", 9)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requiredLevelId").doesNotExist())
                .andExpect(jsonPath("$.data.players.total").value(4));
    }

    @Test
    void candidatesRequireOrderManageAndCustomerServiceScope() throws Exception {
        String path = "/cs/order/200/assign/candidates";
        mvc.perform(get(path).with(role("CS", 82))).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/cs/order/200/assign/2").with(role("CS", 82))).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get(path).with(role("CS", 81))).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/cs/order/201/assign/candidates").with(role("CS", 82)))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/cs/order/999/assign/candidates").with(role("CS", 81)))
                .andExpect(jsonPath("$.code").value(404));
        when(staffPermissions.allowed("order.manage")).thenReturn(false);
        mvc.perform(get(path).with(role("CS", 81))).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/admin/order/200/assign/candidates").with(role("ADMIN", 9)))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void writeRechecksLevelCapacityAndCurrentPlayerForReassignment() throws Exception {
        jdbc.update("UPDATE sys_config SET config_value='1' WHERE config_key='order.max_active_per_player'");
        jdbc.update("UPDATE `order` SET status='ASSIGNED',player_id=3 WHERE id=201");
        mvc.perform(get("/cs/order/200/assign/candidates").with(role("CS", 81)))
                .andExpect(jsonPath("$.data.maxConcurrent").value(1))
                .andExpect(jsonPath("$.data.players.records[1].activeOrders").value(1));
        mvc.perform(post("/cs/order/200/assign/3").with(role("CS", 81)))
                .andExpect(jsonPath("$.msg", containsString("已达最大接单数")));
        jdbc.update("UPDATE player SET level=3 WHERE id=2");
        mvc.perform(post("/cs/order/200/assign/2").with(role("CS", 81))).andExpect(jsonPath("$.code").value(403));
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=200", String.class)).isEqualTo("PAID");

        jdbc.update("UPDATE player SET level=2 WHERE id=2");
        mvc.perform(post("/cs/order/200/assign/2").with(role("CS", 81))).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/cs/order/200/assign/candidates").with(role("CS", 81)))
                .andExpect(jsonPath("$.data.players.total").value(1))
                .andExpect(jsonPath("$.data.players.records[0].id").value(3));
        mvc.perform(post("/cs/order/200/assign/2").with(role("CS", 81)))
                .andExpect(jsonPath("$.msg", containsString("无需重复指派")));
        jdbc.update("UPDATE `order` SET status='CANCELLED' WHERE id=201");
        mvc.perform(post("/cs/order/200/assign/3").with(role("CS", 81))).andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT player_id FROM `order` WHERE id=200", Long.class)).isEqualTo(3L);
    }

    @Test
    void staffTeamCandidatesAndWritesFollowTheSameExactLevelRule() throws Exception {
        teamOrder();
        mvc.perform(get("/cs/order/200/teammates/candidates").with(role("CS", 82)))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/cs/order/200/teammates/candidates").with(role("CS", 81)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requiredLevelName").value("下单时等级"))
                .andExpect(jsonPath("$.data.players.total").value(1))
                .andExpect(jsonPath("$.data.players.records[0].id").value(3));

        mvc.perform(staffInvite(4)).andExpect(jsonPath("$.code").value(403));
        mvc.perform(staffInvite(3)).andExpect(jsonPath("$.code").value(200));
        jdbc.update("UPDATE player SET level=3 WHERE id=3");
        mvc.perform(post("/player/order/200/accept-invite").with(role("PLAYER", 3)))
                .andExpect(jsonPath("$.code").value(403));
        assertThat(jdbc.queryForObject("SELECT status FROM order_player WHERE order_id=200 AND player_id=3", String.class))
                .isEqualTo("INVITED");

        jdbc.update("UPDATE player SET level=2 WHERE id=3");
        mvc.perform(post("/player/order/200/accept-invite").with(role("PLAYER", 3)))
                .andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT status FROM order_player WHERE order_id=200 AND player_id=3", String.class))
                .isEqualTo("ACCEPTED");
    }

    @Test
    void staffTeamReplacementCannotBypassTheLevelRule() throws Exception {
        teamOrder();
        mvc.perform(staffInvite(3)).andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/player/order/200/accept-invite").with(role("PLAYER", 3)))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/admin/order/200/teammates/candidates").with(role("ADMIN", 9)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.players.total").value(0));
        mvc.perform(post("/admin/order/200/teammates/invite").with(role("ADMIN", 9)).contentType("application/json")
                        .content("""
                                {"inviteePlayerId":4,"replacedTeammateId":3,"splitType":"FIFTY_FIFTY","reason":"测试更换"}
                                """))
                .andExpect(jsonPath("$.code").value(403));
        assertThat(jdbc.queryForObject("SELECT status FROM order_player WHERE order_id=200 AND player_id=3", String.class))
                .isEqualTo("ACCEPTED");
    }

    @Test
    void playerInitiatedTeamInvitationsKeepTheirExistingBehavior() throws Exception {
        teamOrder();
        mvc.perform(get("/player/order/teammate/available").param("orderId", "200").with(role("PLAYER", 2)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.players.total").value(3));
        mvc.perform(post("/player/order/200/invite-teammate").with(role("PLAYER", 2))
                .contentType("application/json").content(inviteBody(4))).andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/player/order/200/accept-invite").with(role("PLAYER", 4)))
                .andExpect(jsonPath("$.code").value(200));
    }

    private void teamOrder() {
        jdbc.update("UPDATE `order` SET status='WAITING_TEAMMATE',player_id=2,required_player_count=2 WHERE id=200");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder staffInvite(int id) {
        return post("/cs/order/200/teammates/invite").with(role("CS", 81))
                .contentType("application/json").content(inviteBody(id));
    }

    private String inviteBody(int id) {
        return "{\"inviteePlayerId\":" + id + ",\"splitType\":\"FIFTY_FIFTY\"}";
    }

    private void player(int id, String nickname, int level, String status, int online, int deleted) {
        jdbc.update("INSERT INTO player(id,openid,nickname,level,status,is_online,deleted) VALUES(?,?,?,?,?,?,?)",
                id, "fixture-" + id, nickname, level, status, online, deleted);
    }

    private RequestPostProcessor role(String type, long id) {
        return authentication(new UsernamePasswordAuthenticationToken(Map.of("userId", id, "userType", type), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type))));
    }
}
