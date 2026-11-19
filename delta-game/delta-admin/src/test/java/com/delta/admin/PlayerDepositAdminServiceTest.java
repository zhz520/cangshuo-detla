package com.delta.admin;

import com.delta.player.service.PlayerDepositService;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.pay.service.PaymentService;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerDepositAdminServiceTest {
    private JdbcTemplate jdbc;
    private PaymentService payments;
    private CrossModuleMapper identities;
    private SysConfigService config;
    private PlayerDepositService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:player-deposit-admin;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,openid VARCHAR(64),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE player(id BIGINT PRIMARY KEY,openid VARCHAR(64),nickname VARCHAR(64),level INT,deleted INT DEFAULT 0,deposit_override_amount DECIMAL(10,2) DEFAULT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE player_level(id INT PRIMARY KEY,name VARCHAR(30),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE payment(id BIGINT AUTO_INCREMENT PRIMARY KEY,payment_no VARCHAR(32),user_id BIGINT," +
                "biz_type VARCHAR(20),amount DECIMAL(10,2),status VARCHAR(20),pay_method VARCHAR(20)," +
                "paid_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,refund_reason VARCHAR(255),refund_time TIMESTAMP)");
        jdbc.update("INSERT INTO `user`(id,openid) VALUES(7,'openid-seven')");
        jdbc.update("INSERT INTO player(id,openid,nickname,level) VALUES(1,'openid-seven','测试打手',2)");
        jdbc.update("INSERT INTO player_level(id,name) VALUES(2,'高级') ");
        jdbc.update("INSERT INTO payment(payment_no,user_id,biz_type,amount,status,pay_method,paid_at,created_at) " +
                "VALUES('P-PAID-1',7,'PLAYER_DEPOSIT',100.00,'PAID','WECHAT_OFFICIAL','2026-11-18 00:05:43','2026-11-18 00:05:36')");
        jdbc.update("INSERT INTO payment(payment_no,user_id,biz_type,amount,status,pay_method,paid_at,created_at) " +
                "VALUES('P-PAID-2',7,'PLAYER_DEPOSIT',50.00,'PAID','WECHAT_OFFICIAL','2026-11-18 00:33:36','2026-11-18 00:33:30')");
        jdbc.update("INSERT INTO payment(payment_no,user_id,biz_type,amount,status,pay_method,created_at) " +
                "VALUES('P-PAYING',7,'PLAYER_DEPOSIT',200.00,'PAYING','WECHAT_OFFICIAL','2026-11-18 00:32:04')");
        payments = mock(PaymentService.class);
        identities = mock(CrossModuleMapper.class);
        config = mock(SysConfigService.class);
        when(identities.selectUserIdByPlayerId(1L)).thenReturn(7L);
        when(config.getConfigValue("player.deposit_required", "true")).thenReturn("true");
        when(config.getConfigValue("player.deposit_mode", "REQUIRED")).thenReturn("BEFORE_WITHDRAW");
        when(payments.playerDepositRequiredAmount(7L)).thenReturn(new BigDecimal("150.00"));
        when(payments.playerDepositPaidAmount(7L)).thenReturn(new BigDecimal("150.00"));
        service = new PlayerDepositService(jdbc, payments, identities, config);
    }

    @Test
    void summaryUsesPaidRowsAndKeepsPendingReceiptsVisible() {
        var summary = service.summary(1L);
        assertThat(summary.userId()).isEqualTo(7L);
        assertThat(summary.mode()).isEqualTo("BEFORE_WITHDRAW");
        assertThat(summary.requiredAmount()).isEqualByComparingTo("150.00");
        assertThat(summary.paidAmount()).isEqualByComparingTo("150.00");
        assertThat(summary.outstandingAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.status()).isEqualTo("PAID");
        assertThat(summary.paymentCount()).isEqualTo(2);
        assertThat(summary.lastPaidAt()).isEqualTo("2026-11-18 00:33:36");
        assertThat(summary.overrideAmount()).isNull();
    }

    @Test
    void perPlayerOverrideCanBeSetAndCleared() {
        var updated = service.setOverrideAmount(1L, new BigDecimal("66.50"));
        assertThat(updated.overrideAmount()).isEqualByComparingTo("66.50");
        assertThat(jdbc.queryForObject("SELECT deposit_override_amount FROM player WHERE id=1", BigDecimal.class))
                .isEqualByComparingTo("66.50");
        var cleared = service.setOverrideAmount(1L, null);
        assertThat(cleared.overrideAmount()).isNull();
    }

    @Test
    void paymentHistoryIncludesClosedAndPartialAttempts() {
        var rows = service.payments(1L);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).paymentNo()).isEqualTo("P-PAID-2");
        assertThat(rows.get(1).status()).isEqualTo("PAYING");
        assertThat(rows.get(2).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void globalListFiltersByPaymentNumberAndReturnsPlayerIdentity() {
        var page = service.list("P-PAID", "PAID", 1, 20);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).hasSize(2);
        assertThat(page.records().get(0).get("playerId")).isEqualTo(1L);
        assertThat(page.records().get(0).get("playerName")).isEqualTo("测试打手");
        assertThat(page.records().get(0).get("levelName")).isEqualTo("高级");
        assertThat(page.records().get(0).get("status")).isEqualTo("PAID");
    }
}
