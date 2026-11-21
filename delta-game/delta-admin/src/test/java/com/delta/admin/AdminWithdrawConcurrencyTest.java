package com.delta.admin;

import com.delta.finance.controller.AdminWithdrawController;
import com.delta.finance.service.WithdrawReviewService;
import com.delta.common.exception.BusinessException;
import com.delta.cs.controller.CsWithdrawController;
import com.delta.pay.service.TransactionService;
import com.delta.pay.service.impl.TransactionServiceImpl;
import com.delta.player.entity.Withdraw;
import com.delta.player.service.PlayerAccountService;
import com.delta.player.service.PlayerService;
import com.delta.player.service.impl.PlayerWalletServiceImpl;
import com.delta.player.service.impl.WithdrawServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(classes = AdminWithdrawConcurrencyTest.Config.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
        "spring.autoconfigure.exclude=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure"
})
@Sql("/withdraw-schema.sql")
class AdminWithdrawConcurrencyTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        boolean mysql = MySqlTestSupport.enabled("WITHDRAW_TEST_MYSQL");
        registry.add("spring.datasource.url", () -> mysql
                ? MySqlTestSupport.jdbcUrl("withdraw_qa")
                : "jdbc:h2:mem:withdraw-review;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL,TRANSACTION");
        registry.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> mysql ? MySqlTestSupport.username() : "sa");
        registry.add("spring.datasource.password", () -> mysql ? MySqlTestSupport.password() : "");
    }

    @Configuration
    @EnableAutoConfiguration(excludeName = "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @MapperScan({"com.delta.player.mapper", "com.delta.pay.mapper"})
    @Import({AdminWithdrawController.class, CsWithdrawController.class, WithdrawReviewService.class, WithdrawServiceImpl.class,
            PlayerWalletServiceImpl.class, TransactionServiceImpl.class})
    static class Config {}

    @Autowired AdminWithdrawController controller;
    @Autowired CsWithdrawController customerServiceController;
    @Autowired JdbcTemplate jdbc;
    @SpyBean TransactionService transactions;
    @MockBean PlayerService players;
    @MockBean PlayerAccountService accounts;

    @BeforeEach
    void seed() {
        // Keep the application timestamp safely after the request timestamp even
        // when the test host clock and the database clock tick across a boundary.
        jdbc.update("INSERT INTO withdraw(id,player_id,amount,status,created_at) VALUES(1,1,100.00,'PENDING',TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO player_wallet(player_id,balance,frozen_amount,pending_income,total_income) VALUES(1,50.00,100.00,0,0)");
    }

    @Test
    void concurrentApprovalsConsumeFrozenFundsOnlyOnce() throws Exception {
        var results = race(
                () -> controller.process(1L, approval()),
                () -> controller.process(1L, approval()));

        assertThat(results).hasSize(2).contains("OK");
        assertThat(results).filteredOn(result -> !"OK".equals(result)).allMatch(result -> result.contains("已处理"));
        assertThat(wallet("frozen_amount")).isEqualByComparingTo("0.00");
        assertThat(wallet("balance")).isEqualByComparingTo("50.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \u0060transaction\u0060 WHERE type='WITHDRAW_COMPLETE'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM withdraw WHERE id=1", String.class)).isEqualTo("COMPLETED");
    }

    @Test
    void concurrentApprovalAndRejectCannotBothWin() throws Exception {
        var results = race(
                () -> controller.process(1L, approval()),
                () -> controller.reject(1L, "账号异常"));

        assertThat(results).contains("OK");
        assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(wallet("frozen_amount")).isEqualByComparingTo("0.00");
        String status = jdbc.queryForObject("SELECT status FROM withdraw WHERE id=1", String.class);
        if ("COMPLETED".equals(status)) {
            assertThat(wallet("balance")).isEqualByComparingTo("50.00");
            assertThat(jdbc.queryForObject("SELECT type FROM \u0060transaction\u0060", String.class)).isEqualTo("WITHDRAW_COMPLETE");
        } else {
            assertThat(status).isEqualTo("REJECTED");
            assertThat(wallet("balance")).isEqualByComparingTo("150.00");
            assertThat(jdbc.queryForObject("SELECT type FROM \u0060transaction\u0060", String.class)).isEqualTo("WITHDRAW_REJECT");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \u0060transaction\u0060", Long.class)).isEqualTo(1L);
    }

    @Test
    void concurrentAdminApprovalAndCustomerServiceRejectRecordOneDecision() throws Exception {
        var results = race(
                () -> controller.process(1L, approval()),
                () -> customerServiceController.reject(1L, Map.of("reason", "核对未通过")));

        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).filteredOn(result -> !"OK".equals(result)).allMatch(result -> result.contains("已处理"));
        assertThat(wallet("frozen_amount")).isEqualByComparingTo("0.00");
        var status = jdbc.queryForObject("SELECT status FROM withdraw WHERE id=1", String.class);
        assertThat(status).isIn("COMPLETED", "REJECTED");
        boolean completed = "COMPLETED".equals(status);
        assertThat(wallet("balance")).isEqualByComparingTo(completed ? "50.00" : "150.00");
        assertThat(jdbc.queryForList("SELECT type FROM \u0060transaction\u0060 WHERE related_withdraw_id=1", String.class))
                .containsExactly(completed ? "WITHDRAW_COMPLETE" : "WITHDRAW_REJECT");
    }

    @Test
    void failedCustomerServiceLedgerWriteRollsBackWalletAndWithdrawal() {
        doThrow(new IllegalStateException("synthetic ledger failure")).when(transactions)
                .record(eq("WITHDRAW_COMPLETE"), eq("PLAYER"), eq(1L), any(), any(), any(), isNull(), isNull(), eq(1L), anyString());

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 92L, "userType", "CS"), null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            assertThatThrownBy(() -> customerServiceController.approve(1L, approval()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("synthetic ledger failure");
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(wallet("frozen_amount")).isEqualByComparingTo("100.00");
        assertThat(wallet("balance")).isEqualByComparingTo("50.00");
        assertThat(jdbc.queryForObject("SELECT status FROM withdraw WHERE id=1", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \u0060transaction\u0060", Long.class)).isZero();
    }


    @Test
    void approvalWithoutProofIsRejectedBeforeWalletMutation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.process(1L, new Withdraw()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("打款凭证");
        assertThat(wallet("frozen_amount")).isEqualByComparingTo("100.00");
        assertThat(wallet("balance")).isEqualByComparingTo("50.00");
        assertThat(jdbc.queryForObject("SELECT status FROM withdraw WHERE id=1", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \u0060transaction\u0060", Long.class)).isZero();
    }

    @Test
    void rejectWithoutReasonIsRejectedBeforeWalletMutation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.reject(1L, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("拒绝原因");
        assertThat(wallet("frozen_amount")).isEqualByComparingTo("100.00");
        assertThat(wallet("balance")).isEqualByComparingTo("50.00");
        assertThat(jdbc.queryForObject("SELECT status FROM withdraw WHERE id=1", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \u0060transaction\u0060", Long.class)).isZero();
    }

    private Withdraw approval() {
        var review = new Withdraw();
        review.setProofImage("/file/proof.png");
        review.setPayMethod("ALIPAY");
        review.setProcessedAt(LocalDateTime.now());
        return review;
    }

    private BigDecimal wallet(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM player_wallet WHERE player_id=1", BigDecimal.class);
    }

    @SafeVarargs
    private final List<String> race(Callable<?>... calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.length);
        var barrier = new CyclicBarrier(calls.length);
        try {
            var futures = java.util.Arrays.stream(calls)
                    .map(call -> pool.submit(() -> {
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                                Map.of("userId", 91L, "userType", "ADMIN"), null, List.of()));
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            call.call();
                            return "OK";
                        } catch (BusinessException ex) {
                            return ex.getMessage();
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    }))
                    .toList();
            return futures.stream().map(future -> {
                try {
                    return future.get(15, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }).toList();
        } finally {
            pool.shutdownNow();
        }
    }
}
