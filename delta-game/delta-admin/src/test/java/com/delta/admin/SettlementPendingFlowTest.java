package com.delta.admin;

import com.delta.admin.job.PendingIncomeReleaseTask;
import com.delta.admin.listener.SettlementEventListener;
import com.delta.admin.service.SettlementRecoveryService;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderService;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerWalletService;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SettlementPendingFlowTest {
    @Test
    void confirmedOrderCreatesPendingIncomeAndDoesNotMakeItWithdrawable() {
        DriverManagerDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY, amount DECIMAL(12,2), status VARCHAR(20), settled INT, player_id BIGINT, order_no VARCHAR(40), product_name VARCHAR(100), commission_rate DECIMAL(5,4))");
        jdbc.execute("CREATE TABLE order_finance_snapshot(order_id BIGINT PRIMARY KEY, cs_rate DECIMAL(5,4), cs_id BIGINT)");
        jdbc.execute("CREATE TABLE order_settlement_ledger(order_id BIGINT PRIMARY KEY, amount DECIMAL(12,2), club_amount DECIMAL(12,2), player_amount DECIMAL(12,2), cs_amount DECIMAL(12,2), cs_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS refund_request(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE player_income_settlement(id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, player_id BIGINT, role VARCHAR(20), amount DECIMAL(12,2), reversed_amount DECIMAL(12,2), status VARCHAR(20), release_at TIMESTAMP, released_at TIMESTAMP, attempts INT, last_error VARCHAR(255), next_attempt_at TIMESTAMP, remark VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS refund_request(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32))");
        jdbc.update("INSERT INTO `order` VALUES(100,100,'CONFIRMED',0,1,'ORDER100','护航',0.2)");

        Order order = new Order();
        order.setId(100L); order.setPlayerId(1L); order.setAmount(new BigDecimal("100.00"));
        order.setCommissionRate(new BigDecimal("0.20")); order.setStatus("CONFIRMED"); order.setSettled(0);
        OrderService orders = mock(OrderService.class);
        when(orders.getOne(any())).thenReturn(order);
        OrderPlayerService players = mock(OrderPlayerService.class);
        when(players.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        PlayerWallet wallet = new PlayerWallet();
        wallet.setPlayerId(1L); wallet.setBalance(BigDecimal.ZERO); wallet.setPendingIncome(BigDecimal.ZERO); wallet.setTotalIncome(BigDecimal.ZERO);
        PlayerWalletService wallets = mock(PlayerWalletService.class);
        when(wallets.getByPlayerId(1L)).thenReturn(wallet);
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue(eq("settlement.commission_rate"), anyString())).thenReturn("0.2");
        when(configs.getConfigValue(eq("settlement.hold_hours"), anyString())).thenReturn("72");

        new SettlementEventListener(orders, jdbc, players, wallets, configs).settleOrder(100L);

        assertThat(wallet.getBalance()).isEqualByComparingTo("0.00");
        assertThat(wallet.getPendingIncome()).isEqualByComparingTo("80.00");
        assertThat(jdbc.queryForObject("SELECT status FROM player_income_settlement WHERE order_id=100", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT amount FROM player_income_settlement WHERE order_id=100", BigDecimal.class)).isEqualByComparingTo("80.00");
    }

    @Test
    void releaseTaskMovesOnlyDuePendingIncomeIntoAvailableBalance() {
        DriverManagerDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY, amount DECIMAL(12,2), status VARCHAR(20), settled INT, player_id BIGINT, order_no VARCHAR(40), product_name VARCHAR(100), commission_rate DECIMAL(5,4))");
        jdbc.execute("CREATE TABLE player_wallet(id BIGINT PRIMARY KEY, player_id BIGINT, balance DECIMAL(12,2), pending_income DECIMAL(12,2), frozen_amount DECIMAL(12,2), total_income DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE player_income_settlement(id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, player_id BIGINT, role VARCHAR(20), amount DECIMAL(12,2), reversed_amount DECIMAL(12,2), status VARCHAR(20), release_at TIMESTAMP, released_at TIMESTAMP, attempts INT, last_error VARCHAR(255), next_attempt_at TIMESTAMP, remark VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS refund_request(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE complaint(id BIGINT PRIMARY KEY, order_id BIGINT, status VARCHAR(20))");
        jdbc.update("INSERT INTO `order` VALUES(100,100,'CONFIRMED',1,1,'ORDER100','护航',0.2)");
        jdbc.update("INSERT INTO player_wallet VALUES(1,1,0,80,0,0)");
        jdbc.update("INSERT INTO player_income_settlement(order_id,player_id,role,amount,reversed_amount,status,release_at,next_attempt_at) VALUES(100,1,'PRIMARY',80,0,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        PlayerWallet wallet = new PlayerWallet();
        wallet.setPlayerId(1L); wallet.setBalance(BigDecimal.ZERO); wallet.setPendingIncome(new BigDecimal("80.00")); wallet.setTotalIncome(BigDecimal.ZERO);
        PlayerWalletService wallets = mock(PlayerWalletService.class);
        when(wallets.getByPlayerId(1L)).thenReturn(wallet);
        TransactionService transactions = mock(TransactionService.class);
        SettlementRecoveryService recovery = mock(SettlementRecoveryService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        var task = new PendingIncomeReleaseTask(jdbc, wallets, transactions, recovery,
                mock(SettlementEventListener.class), events, new DataSourceTransactionManager(dataSource));

        task.run();

        assertThat(wallet.getBalance()).isEqualByComparingTo("80.00");
        assertThat(wallet.getPendingIncome()).isEqualByComparingTo("0.00");
        assertThat(wallet.getTotalIncome()).isEqualByComparingTo("80.00");
        assertThat(jdbc.queryForObject("SELECT status FROM player_income_settlement WHERE order_id=100", String.class)).isEqualTo("RELEASED");
        verify(transactions).record(eq("INCOME"), eq("PLAYER"), eq(1L), eq(new BigDecimal("80.00")),
                any(BigDecimal.class), any(BigDecimal.class), eq(100L), isNull(), isNull(), anyString());
    }

    @Test
    void refundBeforeReleaseReducesPendingIncomeInsteadOfAvailableBalance() {
        DriverManagerDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY, amount DECIMAL(12,2), status VARCHAR(20), settled INT)");
        jdbc.execute("CREATE TABLE payment(order_id BIGINT, status VARCHAR(20), refund_amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE player_income_settlement(id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, player_id BIGINT, role VARCHAR(20), amount DECIMAL(12,2), reversed_amount DECIMAL(12,2), status VARCHAR(20), release_at TIMESTAMP, released_at TIMESTAMP, attempts INT, last_error VARCHAR(255), next_attempt_at TIMESTAMP, remark VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS refund_request(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE player_refund_recovery(order_id BIGINT,player_id BIGINT,refund_share DECIMAL(12,2),recovered DECIMAL(12,2),remaining DECIMAL(12,2),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(order_id,player_id))");
        jdbc.execute("CREATE TABLE order_settlement_ledger(order_id BIGINT PRIMARY KEY,amount DECIMAL(12,2),club_amount DECIMAL(12,2),player_amount DECIMAL(12,2),cs_amount DECIMAL(12,2),cs_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS refund_request(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE order_settlement_refund(order_id BIGINT PRIMARY KEY,refund_amount DECIMAL(12,2),club_refund DECIMAL(12,2),player_refund DECIMAL(12,2),cs_refund DECIMAL(12,2),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO `order` VALUES(100,100,'CONFIRMED',1)");
        jdbc.update("INSERT INTO payment VALUES(100,'REFUNDED',40)");
        jdbc.update("INSERT INTO player_income_settlement(order_id,player_id,role,amount,reversed_amount,status,release_at,next_attempt_at) VALUES(100,1,'PRIMARY',80,0,'PENDING',DATEADD('HOUR',72,CURRENT_TIMESTAMP),DATEADD('HOUR',72,CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO order_settlement_ledger VALUES(100,100,20,80,0,NULL)");
        PlayerWallet wallet = new PlayerWallet();
        wallet.setPlayerId(1L); wallet.setBalance(BigDecimal.ZERO); wallet.setPendingIncome(new BigDecimal("80.00")); wallet.setTotalIncome(BigDecimal.ZERO);
        PlayerWalletService wallets = mock(PlayerWalletService.class);
        when(wallets.getByPlayerId(1L)).thenReturn(wallet);
        TransactionService transactions = mock(TransactionService.class);
        var recovery = new SettlementRecoveryService(jdbc, wallets, transactions);

        recovery.refunded(new com.delta.common.event.BusinessEvent(this, "ORDER_REFUNDED", "USER", 7L, 100L, "退款"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("0.00");
        assertThat(wallet.getPendingIncome()).isEqualByComparingTo("48.00");
        assertThat(jdbc.queryForObject("SELECT reversed_amount FROM player_income_settlement WHERE order_id=100", BigDecimal.class)).isEqualByComparingTo("32.00");
        assertThat(jdbc.queryForObject("SELECT remaining FROM player_refund_recovery WHERE order_id=100 AND player_id=1", BigDecimal.class)).isEqualByComparingTo("0.00");
    }

    private DriverManagerDataSource database() {
        String url = "jdbc:h2:mem:settlement" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        return new DriverManagerDataSource(url, "sa", "");
    }
}
