package com.delta.admin;

import com.delta.common.chat.service.ChatSessionService;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.service.TokenService;
import com.delta.common.security.service.VerificationService;
import com.delta.order.service.OrderService;
import com.delta.pay.service.PaymentService;
import com.delta.pay.service.WechatOfficialPayService;
import com.delta.pay.controller.PayController;
import com.delta.pay.config.WxPayConfiguration;
import com.delta.player.controller.PlayerHomeController;
import com.delta.player.controller.WithdrawController;
import com.delta.player.service.impl.PlayerAccountServiceImpl;
import com.delta.player.service.impl.WithdrawServiceImpl;
import com.delta.product.service.ProductService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = PlayerBusinessRegressionTest.Config.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:player-business-regression;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource", "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@Sql(scripts = "/reward-schema.sql", config = @org.springframework.test.context.jdbc.SqlConfig(encoding = "UTF-8"))
class PlayerBusinessRegressionTest {
    @Configuration
    @Import({RewardGrabIntegrationTest.Config.class, PlayerHomeController.class, WithdrawController.class, PayController.class,
            PlayerAccountServiceImpl.class, WithdrawServiceImpl.class, com.delta.common.mybatis.handler.MetaObjectHandlerImpl.class})
    static class Config {}

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orders;
    @MockBean TokenService tokens;
    @MockBean VerificationService verification;
    @MockBean ProductService products;
    @MockBean com.delta.order.service.PlayerReplaceRequestService replacements;
    @MockBean RedisService redis;
    @MockBean org.springframework.data.redis.core.StringRedisTemplate strings;
    @MockBean ChatSessionService chats;
    @MockBean PaymentService payments;
    @MockBean WechatOfficialPayService officialPay;
    @MockBean WxPayConfiguration wxPayConfiguration;

    @BeforeEach void withdrawalFixtures() {
        jdbc.update("UPDATE player SET real_name='测试收款人' WHERE id=1");
        jdbc.update("UPDATE player SET real_name='另一收款人' WHERE id=2");
        jdbc.execute("CREATE TABLE complaint(id BIGINT PRIMARY KEY,player_id BIGINT,status VARCHAR(20))");
        jdbc.execute("""
                CREATE TABLE player_account(id BIGINT AUTO_INCREMENT PRIMARY KEY,player_id BIGINT,type VARCHAR(20),
                account_name VARCHAR(64),account_no VARCHAR(100),qrcode_url VARCHAR(512),is_default INT DEFAULT 0,
                deleted INT DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE withdraw(id BIGINT AUTO_INCREMENT PRIMARY KEY,player_id BIGINT,account_id BIGINT,
                account_type_snapshot VARCHAR(20),account_name_snapshot VARCHAR(64),account_no_snapshot VARCHAR(100),
                account_qrcode_snapshot VARCHAR(512),amount DECIMAL(12,2),status VARCHAR(20),pay_method VARCHAR(20),
                proof_image VARCHAR(512),reject_reason VARCHAR(255),processed_by BIGINT,processed_at TIMESTAMP,
                last_notify_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.update("INSERT INTO player_account(id,player_id,type,account_name,account_no) VALUES(10,1,'ALIPAY','测试收款人','test-account'),(20,2,'ALIPAY','另一收款人','other-account')");
        jdbc.update("INSERT INTO sys_config(config_key,config_value) VALUES('player.deposit_mode','NONE')");
    }

    RequestPostProcessor player(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(Map.of("userId", id, "userType", "PLAYER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_PLAYER"))));
    }

    void invitation(String status) {
        jdbc.update("UPDATE `order` SET status=?,player_id=1,required_player_count=2 WHERE id=200", status);
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status,accepted_at) VALUES(200,1,'PRIMARY','ACCEPTED',?)", LocalDateTime.now());
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status,invite_deadline) VALUES(200,2,'TEAMMATE','INVITED',?)", LocalDateTime.now().plusMinutes(5));
    }

    void invitationUnchanged() {
        assertThat(jdbc.queryForObject("SELECT status FROM order_player WHERE order_id=200 AND player_id=2", String.class)).isEqualTo("INVITED");
        verifyNoInteractions(chats);
    }

    @Test void offDutyPlayerCannotAcceptATeammateInvitation() throws Exception {
        invitation("WAITING_TEAMMATE");
        jdbc.update("UPDATE player SET is_online=0 WHERE id=2");
        mvc.perform(post("/player/order/200/accept-invite").with(player(2))).andExpect(jsonPath("$.code").value(403));
        invitationUnchanged();
    }

    @Test void expiredInvitationsDoNotBlockReplacementAndTeamCapacityIsEnforced() throws Exception {
        jdbc.update("UPDATE `order` SET status='WAITING_TEAMMATE',player_id=1,required_player_count=2 WHERE id=200");
        jdbc.update("INSERT INTO player(id,openid,nickname,status,level,phone,is_online) VALUES(3,'player-three','普通打手丙','ACTIVE',1,'13900000003',1)");
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status,invited_by,invite_deadline) VALUES(200,2,'TEAMMATE','INVITED',1,?)",LocalDateTime.now().minusMinutes(1));

        mvc.perform(post("/player/order/200/invite-teammate").with(player(1)).contentType("application/json")
                        .content("{\"inviteePlayerId\":3,\"splitType\":\"FIFTY_FIFTY\"}"))
                .andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT status FROM order_player WHERE order_id=200 AND player_id=2",String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM order_player WHERE order_id=200 AND player_id=3",String.class)).isEqualTo("INVITED");

        mvc.perform(post("/player/order/200/accept-invite").with(player(3))).andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/player/order/200/invite-teammate").with(player(1)).contentType("application/json")
                        .content("{\"inviteePlayerId\":2,\"splitType\":\"FIFTY_FIFTY\"}"))
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test void frozenPlayerCannotAcceptATeammateInvitation() throws Exception {
        invitation("WAITING_TEAMMATE");
        jdbc.update("UPDATE player SET status='FROZEN' WHERE id=2");
        mvc.perform(post("/player/order/200/accept-invite").with(player(2))).andExpect(jsonPath("$.code").value(403));
        invitationUnchanged();
    }

    @Test void refundedOrderCannotAcquireANewTeammate() throws Exception {
        invitation("REFUNDED");
        mvc.perform(post("/player/order/200/accept-invite").with(player(2))).andExpect(jsonPath("$.code").value(409));
        invitationUnchanged();
    }

    @Test void teammateAcceptanceRespectsTheSameActiveOrderLimitAsPrimaryIntake() throws Exception {
        invitation("WAITING_TEAMMATE");
        jdbc.update("UPDATE sys_config SET config_value='1' WHERE config_key='order.max_active_per_player'");
        jdbc.update("UPDATE `order` SET status='IN_PROGRESS',player_id=2 WHERE id=201");
        mvc.perform(post("/player/order/200/accept-invite").with(player(2))).andExpect(jsonPath("$.code").value(400));
        invitationUnchanged();
    }

    @Test void eligibleTeammateCanAcceptOnceAndTheTeamBecomesReady() throws Exception {
        invitation("WAITING_TEAMMATE");
        mvc.perform(post("/player/order/200/accept-invite").with(player(2))).andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=200", String.class)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT status FROM order_player WHERE order_id=200 AND player_id=2", String.class)).isEqualTo("ACCEPTED");
        mvc.perform(post("/player/order/200/accept-invite").with(player(2))).andExpect(jsonPath("$.code").value(500));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_progress WHERE order_id=200 AND type='TEAMMATE_ACCEPTED'", Integer.class)).isEqualTo(1);
    }

    @Test void multiMemberTeamBecomesReadyOnlyAfterEveryRequiredInviteIsAccepted() throws Exception {
        jdbc.update("UPDATE `order` SET status='WAITING_TEAMMATE',player_id=1,required_player_count=3 WHERE id=200");
        jdbc.update("INSERT INTO player(id,openid,nickname,status,level,phone,is_online) VALUES(3,'player-three','普通打手丙','ACTIVE',1,'13900000003',1)");
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status) VALUES(200,1,'PRIMARY','ACCEPTED')");
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status,invite_deadline) VALUES(200,2,'TEAMMATE','INVITED',?),(200,3,'TEAMMATE','INVITED',?)",
                LocalDateTime.now().plusMinutes(5), LocalDateTime.now().plusMinutes(5));

        mvc.perform(post("/player/order/200/accept-invite").with(player(2))).andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=200", String.class)).isEqualTo("WAITING_TEAMMATE");
        mvc.perform(post("/player/order/200/accept-invite").with(player(3))).andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=200", String.class)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_player WHERE order_id=200 AND role='TEAMMATE' AND status='ACCEPTED'", Integer.class)).isEqualTo(2);
    }

    @Test void teamCannotStartUntilEveryRequiredTeammateHasAccepted() throws Exception {
        jdbc.update("UPDATE `order` SET status='ACCEPTED',player_id=1,required_player_count=3 WHERE id=200");
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status) VALUES(200,1,'PRIMARY','ACCEPTED'),(200,2,'TEAMMATE','ACCEPTED')");
        mvc.perform(post("/player/order/200/start").with(player(1))).andExpect(jsonPath("$.code").value(409));
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=200", String.class)).isEqualTo("ACCEPTED");

        jdbc.update("INSERT INTO player(id,openid,nickname,status,level,phone,is_online) VALUES(3,'player-three','普通打手丙','ACTIVE',1,'13900000003',1)");
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status) VALUES(200,3,'TEAMMATE','ACCEPTED')");
        mvc.perform(post("/player/order/200/start").with(player(1))).andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=200", String.class)).isEqualTo("IN_PROGRESS");
    }

    @Test void invitationRejectsCombinedSharesAboveTheSettlementIncome() throws Exception {
        jdbc.update("UPDATE `order` SET status='ACCEPTED',player_id=1,required_player_count=3,commission_rate=0.2 WHERE id=200");
        jdbc.update("INSERT INTO player(id,openid,nickname,status,level,phone,is_online) VALUES(3,'player-three','普通打手丙','ACTIVE',1,'13900000003',1)");
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status) VALUES(200,1,'PRIMARY','ACCEPTED')");
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status,split_type,split_amount) VALUES(200,2,'TEAMMATE','ACCEPTED','CUSTOM',30)");

        mvc.perform(post("/player/order/200/invite-teammate").with(player(1)).contentType("application/json")
                        .content("{\"inviteePlayerId\":3,\"splitType\":\"CUSTOM\",\"customAmount\":20}"))
                .andExpect(jsonPath("$.code").value(409));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_player WHERE order_id=200 AND player_id=3", Integer.class)).isZero();
    }

    @Test void offDutyPlayerCannotBeAssignedANewOrder() {
        jdbc.update("UPDATE player SET is_online=0 WHERE id=2");
        assertThatThrownBy(() -> orders.assignOrder(200L, 2L)).isInstanceOf(com.delta.common.exception.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT player_id FROM `order` WHERE id=200", Long.class)).isNull();
    }

    @Test void todayCompletedIncludesAcceptedTeammatesOnceAndExcludesRejectedInvites() throws Exception {
        jdbc.update("UPDATE `order` SET complete_time=? WHERE id=100", LocalDateTime.now());
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status) VALUES(100,1,'PRIMARY','ACCEPTED'),(100,2,'TEAMMATE','ACCEPTED'),(102,2,'TEAMMATE','REJECTED')");
        mvc.perform(get("/player/home").with(player(1))).andExpect(jsonPath("$.data.todayCompleted").value(2));
        mvc.perform(get("/player/home").with(player(2))).andExpect(jsonPath("$.data.todayCompleted").value(1));
        jdbc.update("UPDATE `order` SET complete_time=? WHERE id=100", LocalDateTime.now().minusDays(1));
        mvc.perform(get("/player/home").with(player(2))).andExpect(jsonPath("$.data.todayCompleted").value(0));
    }

    @Test void homePreviewProtectsCustomerDetailsBeforeTheOrderIsAccepted() throws Exception {
        jdbc.update("UPDATE `order` SET game_account='private-account',contact='private-phone',remark='private-note' WHERE id=200");
        mvc.perform(get("/player/home").with(player(1)))
                .andExpect(jsonPath("$.data.availableOrders[0].gameAccount").isEmpty())
                .andExpect(jsonPath("$.data.availableOrders[0].contact").isEmpty())
                .andExpect(jsonPath("$.data.availableOrders[0].remark").isEmpty())
                .andExpect(jsonPath("$.data.availableOrders[0].userId").isEmpty())
                .andExpect(jsonPath("$.data.availableOrders[0].extraFields").isEmpty());
    }

    void beforeWithdrawDeposit(String paid) {
        jdbc.update("UPDATE sys_config SET config_value='BEFORE_WITHDRAW' WHERE config_key='player.deposit_mode'");
        jdbc.update("UPDATE player SET openid='user-seven' WHERE id=1");
        when(payments.playerDepositRequiredAmount(7L)).thenReturn(new BigDecimal("100"));
        when(payments.playerDepositPaidAmount(7L)).thenReturn(new BigDecimal(paid));
    }

    @Test void unpaidDepositBlocksWithdrawalWithoutChangingTheWallet() throws Exception {
        beforeWithdrawDeposit("60");
        mvc.perform(get("/player/withdraw/deposit-status").with(player(1)))
                .andExpect(jsonPath("$.data.outstandingAmount").value(40)).andExpect(jsonPath("$.data.needsPayment").value(true));
        mvc.perform(post("/player/withdraw").with(player(1)).contentType("application/json").content("{\"amount\":10,\"accountId\":10}"))
                .andExpect(jsonPath("$.code").value(409));
        assertThat(jdbc.queryForObject("SELECT balance FROM player_wallet WHERE player_id=1", BigDecimal.class)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM withdraw", Integer.class)).isZero();
    }

    @Test void sufficientDepositAllowsWithdrawalAndFreezesTheAmountWithAnAccountSnapshot() throws Exception {
        beforeWithdrawDeposit("100");
        mvc.perform(post("/player/withdraw").with(player(1)).contentType("application/json").content("{\"amount\":10,\"accountId\":10}"))
                .andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT balance FROM player_wallet WHERE player_id=1", BigDecimal.class)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT frozen_amount FROM player_wallet WHERE player_id=1", BigDecimal.class)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT account_no_snapshot FROM withdraw", String.class)).isEqualTo("test-account");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `transaction` WHERE type='WITHDRAW'", Integer.class)).isEqualTo(1);
    }

    @Test void withdrawalCannotUseAnotherPlayersAccount() throws Exception {
        mvc.perform(post("/player/withdraw").with(player(1)).contentType("application/json").content("{\"amount\":10,\"accountId\":20}"))
                .andExpect(jsonPath("$.code").value(404));
        assertThat(jdbc.queryForObject("SELECT balance FROM player_wallet WHERE player_id=1", BigDecimal.class)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM withdraw", Integer.class)).isZero();
    }

    @Test void withdrawalDailyLimitIsEnforcedBeforeAnySecondDebit() throws Exception {
        jdbc.update("INSERT INTO sys_config(config_key,config_value) VALUES('withdraw.max_daily_count','1')");
        mvc.perform(post("/player/withdraw").with(player(1)).contentType("application/json").content("{\"amount\":10,\"accountId\":10}"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/player/withdraw").with(player(1)).contentType("application/json").content("{\"amount\":10,\"accountId\":10}"))
                .andExpect(jsonPath("$.code").value(500));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM withdraw", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM player_wallet WHERE player_id=1", BigDecimal.class)).isEqualByComparingTo("10");
    }

    @Test void outstandingRefundRecoveryBlocksWithdrawal() throws Exception {
        jdbc.update("INSERT INTO player_refund_recovery(order_id,player_id,remaining) VALUES(100,1,5)");
        mvc.perform(post("/player/withdraw").with(player(1)).contentType("application/json").content("{\"amount\":10,\"accountId\":10}"))
                .andExpect(jsonPath("$.code").value(409));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM withdraw", Integer.class)).isZero();
    }

    @Test void concurrentWithdrawalsCannotSpendTheSameBalanceTwice() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Integer> request = () -> {
            start.await();
            String response = mvc.perform(post("/player/withdraw").with(player(1)).contentType("application/json")
                    .content("{\"amount\":15,\"accountId\":10}")).andReturn().getResponse().getContentAsString();
            return com.alibaba.fastjson2.JSON.parseObject(response).getIntValue("code");
        };
        try {
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 500);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM withdraw", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM player_wallet WHERE player_id=1", BigDecimal.class)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT frozen_amount FROM player_wallet WHERE player_id=1", BigDecimal.class)).isEqualByComparingTo("15");
    }

    @Test void transactionHistorySeparatesUsersAndPlayersWithTheSameNumericId() throws Exception {
        jdbc.update("INSERT INTO `transaction`(type,user_type,user_id,amount,balance_before,balance_after) VALUES('INCOME','PLAYER',1,10,0,10),('RECHARGE','USER',1,90,0,90),('RECHARGE','USER',8,80,0,80)");
        mvc.perform(get("/pay/transactions").with(player(1)))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].userType").value("PLAYER"))
                .andExpect(jsonPath("$.data.records[0].amount").value(10));
        var user = authentication(new UsernamePasswordAuthenticationToken(Map.of("userId", 1L, "userType", "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        mvc.perform(get("/pay/transactions").with(user))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].userType").value("USER"))
                .andExpect(jsonPath("$.data.records[0].amount").value(90));
    }

    List<Integer> concurrentInvitationResponses(long firstOrder, long secondOrder) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> responses = new java.util.ArrayList<>();
            for (long orderId : new long[]{firstOrder, secondOrder}) {
                responses.add(executor.submit(() -> {
                    start.await();
                    String body = mvc.perform(post("/player/order/" + orderId + "/accept-invite").with(player(2)))
                            .andReturn().getResponse().getContentAsString();
                    return com.alibaba.fastjson2.JSON.parseObject(body).getIntValue("code");
                }));
            }
            start.countDown();
            return List.of(responses.get(0).get(20, TimeUnit.SECONDS), responses.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void concurrentAcceptanceOfTheSameInvitationHasOnlyOneWinner() throws Exception {
        invitation("WAITING_TEAMMATE");
        assertThat(concurrentInvitationResponses(200, 200)).containsExactlyInAnyOrder(200, 500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_progress WHERE order_id=200 AND type='TEAMMATE_ACCEPTED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_player WHERE order_id=200 AND player_id=2 AND status='ACCEPTED'", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentInvitationsForDifferentOrdersCannotExceedThePlayersConfiguredLimit() throws Exception {
        invitation("WAITING_TEAMMATE");
        jdbc.update("UPDATE sys_config SET config_value='1' WHERE config_key='order.max_active_per_player'");
        jdbc.update("UPDATE `order` SET status='WAITING_TEAMMATE',player_id=1,required_player_count=2 WHERE id=201");
        jdbc.update("INSERT INTO order_player(order_id,player_id,role,status,invite_deadline) VALUES(201,2,'TEAMMATE','INVITED',?)", LocalDateTime.now().plusMinutes(5));
        assertThat(concurrentInvitationResponses(200, 201)).containsExactlyInAnyOrder(200, 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_player WHERE player_id=2 AND status='ACCEPTED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_player WHERE player_id=2 AND status='INVITED'", Integer.class)).isEqualTo(1);
    }
}
