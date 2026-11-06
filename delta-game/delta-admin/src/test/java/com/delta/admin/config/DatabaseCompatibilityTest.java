package com.delta.admin.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseCompatibilityTest {
    @Test
    void addsMissingPlayerLevelDepositColumnIdempotently() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:compatibility;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE player_level(id INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE payment(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE sys_config(id BIGINT AUTO_INCREMENT PRIMARY KEY, config_key VARCHAR(128) UNIQUE, " +
                "config_name VARCHAR(128), config_value TEXT, value_type VARCHAR(32), config_group VARCHAR(64), remark VARCHAR(255))");

        DatabaseCompatibility compatibility = new DatabaseCompatibility(jdbc);
        compatibility.ensure();
        compatibility.ensure();

        Integer columns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='PLAYER_LEVEL' AND column_name='WITHDRAW_DEPOSIT_AMOUNT'",
                Integer.class);
        assertThat(columns).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='PAYMENT' AND column_name='WX_APP_ID'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='EMAIL_NOTICE_QUEUE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_name='EMAIL_NOTICE_QUEUE' AND constraint_type='UNIQUE'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='PLAYER_REWARD'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key='features.reward_grab'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='USER' AND column_name='IS_GUEST'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='GUEST_CHECKOUT_PROFILE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='GUEST_USER_LINK'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key IN ('order.purchase_enabled','order.guest_checkout_enabled')", Integer.class)).isEqualTo(2);
    }

    @Test
    void leavesExistingSchemaUntouched() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:compatibility_existing;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE player_level(id INT PRIMARY KEY, withdraw_deposit_amount DECIMAL(10,2) NOT NULL DEFAULT 25)");
        jdbc.update("INSERT INTO player_level(id) VALUES(1)");

        new DatabaseCompatibility(jdbc).ensure();

        assertThat(jdbc.queryForObject("SELECT withdraw_deposit_amount FROM player_level WHERE id=1", java.math.BigDecimal.class))
                .isEqualByComparingTo("25");
    }
}
