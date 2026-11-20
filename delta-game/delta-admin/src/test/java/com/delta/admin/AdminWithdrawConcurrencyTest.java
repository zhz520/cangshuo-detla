package com.delta.admin;

import com.delta.admin.controller.AdminWithdrawController;
import com.delta.common.exception.BusinessException;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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

@SpringBootTest(classes = AdminWithdrawConcurrencyTest.Config.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:withdraw-review;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
        "spring.autoconfigure.exclude=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure"
})
@Sql("/withdraw-schema.sql")
class AdminWithdrawConcurrencyTest {

    @Configuration
    @EnableAutoConfiguration(excludeName = "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @MapperScan({"com.delta.player.mapper", "com.delta.pay.mapper"})
    @Import({AdminWithdrawController.class, WithdrawServiceImpl.class,
            PlayerWalletServiceImpl.class, TransactionServiceImpl.class})
    static class Config {}

    @Autowired AdminWithdrawController controller;
    @Autowired JdbcTemplate jdbc;
    @MockBean PlayerService players;
    @MockBean PlayerAccountService accounts;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO withdraw(id,player_id,amount,status,created_at) VALUES(1,1,100.00,'PENDING',CURRENT_TIMESTAMP)");
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





