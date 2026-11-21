package com.delta.marketing.controller;

import com.delta.marketing.controller.InviteAdminController;
import com.delta.common.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InviteAdminControllerTest {
    JdbcTemplate jdbc;
    InviteAdminController controller;

    @BeforeEach
    void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:invite-admin-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE invite_reward_debt(id BIGINT PRIMARY KEY,status VARCHAR(16) NOT NULL,recovered_at TIMESTAMP)");
        controller = new InviteAdminController(jdbc);
    }

    @Test
    void pendingDebtCanBeRecoveredOnceAndTheOperationIsIdempotent() {
        jdbc.update("INSERT INTO invite_reward_debt(id,status) VALUES(1,'PENDING')");

        assertThat(controller.markDebtRecovered(1L).getCode()).isEqualTo(200);
        assertThat(controller.markDebtRecovered(1L).getCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM invite_reward_debt WHERE id=1", String.class)).isEqualTo("RECOVERED");
        assertThat(jdbc.queryForObject("SELECT recovered_at FROM invite_reward_debt WHERE id=1", Timestamp.class)).isNotNull();
    }

    @Test
    void missingDebtReturnsNotFoundAndUnexpectedStateReturnsConflict() {
        jdbc.update("INSERT INTO invite_reward_debt(id,status) VALUES(2,'CANCELLED')");
        jdbc.update("INSERT INTO invite_reward_debt(id,status) VALUES(3,'RECOVERED')");
        jdbc.update("UPDATE invite_reward_debt SET recovered_at=? WHERE id=3", Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 9, 30)));

        assertThatThrownBy(() -> controller.markDebtRecovered(99L))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> controller.markDebtRecovered(2L))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        assertThat(controller.markDebtRecovered(3L).getCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT recovered_at FROM invite_reward_debt WHERE id=3", Timestamp.class))
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 9, 30)));
    }
}
