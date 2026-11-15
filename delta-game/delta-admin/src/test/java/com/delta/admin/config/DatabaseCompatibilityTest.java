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
        jdbc.execute("CREATE TABLE wechat_notice_queue(id VARCHAR(36) PRIMARY KEY,dedupe_key VARCHAR(64) NULL," +
                "status VARCHAR(32) NOT NULL DEFAULT 'PENDING',next_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='ANALYTICS_DAILY_VISITOR'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE table_name='WECHAT_OFFICIAL_BINDING' AND index_name='UK_WECHAT_OFFICIAL_OPENID'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE table_name='WECHAT_NOTICE_QUEUE' AND index_name='UK_WECHAT_NOTICE_DEDUPE'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE table_name='WECHAT_NOTICE_QUEUE' AND index_name='IDX_WECHAT_NOTICE_DELIVERY'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key='features.reward_grab'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='USER' AND column_name='IS_GUEST'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='GUEST_CHECKOUT_PROFILE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='GUEST_USER_LINK'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='DISPLAY_PRIVACY'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='INVITE_REWARD_DEBT'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='STATISTICS_DAILY' AND column_name IN ('NEW_ORDER_COUNT','COMPLETED_ORDER_COUNT','NEW_USER_COUNT','TOTAL_AMOUNT')", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key IN ('order.purchase_enabled','order.guest_checkout_enabled','player.directory.enabled')", Integer.class)).isEqualTo(3);
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

    @Test
    void backsUpConflictingWechatBindingsBeforeSkippingTheGlobalUniqueIndex() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:compatibility_wechat_conflict;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE wechat_official_binding(user_type VARCHAR(16) NOT NULL,user_id BIGINT NOT NULL," +
                "openid VARCHAR(128) NOT NULL,PRIMARY KEY(user_type,user_id),UNIQUE(openid,user_type))");
        jdbc.update("INSERT INTO wechat_official_binding(user_type,user_id,openid) VALUES('USER',7,'same-openid-123')");
        jdbc.update("INSERT INTO wechat_official_binding(user_type,user_id,openid) VALUES('ADMIN',8,'same-openid-123')");

        new DatabaseCompatibility(jdbc).ensure();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wechat_official_binding_conflict_backup", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE table_name='WECHAT_OFFICIAL_BINDING' AND index_name='UK_WECHAT_OFFICIAL_OPENID'", Integer.class))
                .isZero();
    }

    @Test
    void removesTheLegacyCouponUniqueConstraintSoPerUserLimitCanExceedOne() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:compatibility_coupon_unique;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE user_coupon(id BIGINT AUTO_INCREMENT PRIMARY KEY,template_id BIGINT NOT NULL," +
                "user_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,claimed_at TIMESTAMP NOT NULL,expires_at TIMESTAMP NOT NULL," +
                "used_order_id BIGINT,used_at TIMESTAMP)");
        jdbc.execute("CREATE UNIQUE INDEX uk_coupon_user_template ON user_coupon(template_id,user_id)");

        new DatabaseCompatibility(jdbc).ensure();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE table_name='USER_COUPON' AND index_name='UK_COUPON_USER_TEMPLATE'", Integer.class)).isZero();
        jdbc.update("INSERT INTO user_coupon(template_id,user_id,status,claimed_at,expires_at) VALUES(1,7,'AVAILABLE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO user_coupon(template_id,user_id,status,claimed_at,expires_at) VALUES(1,7,'AVAILABLE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon", Integer.class)).isEqualTo(2);
    }

    @Test
    void keepsOnlyTheActiveChatSessionUniqueForEachParticipantPair() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:compatibility_chat_session;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_session(id BIGINT AUTO_INCREMENT PRIMARY KEY,id1 BIGINT NOT NULL,id2 BIGINT NOT NULL,status VARCHAR(20))");
        jdbc.execute("CREATE UNIQUE INDEX uk_id1_id2 ON chat_session(id1,id2)");
        jdbc.update("INSERT INTO chat_session(id1,id2,status) VALUES(1,2,'ACTIVE')");
        jdbc.update("INSERT INTO chat_session(id1,id2,status) VALUES(3,4,'CLOSED')");

        new DatabaseCompatibility(jdbc).ensure();
        new DatabaseCompatibility(jdbc).ensure();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE table_name='CHAT_SESSION' AND index_name='UK_ID1_ID2'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE table_name='CHAT_SESSION' AND index_name='UK_CHAT_SESSION_ACTIVE_PAIR'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_pair_slot FROM chat_session WHERE id1=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_pair_slot FROM chat_session WHERE id1=3", Integer.class)).isNull();

        // These statements deliberately omit the marker, just as the old application does.
        jdbc.update("INSERT INTO chat_session(id1,id2,status) VALUES(3,4,'ACTIVE')");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO chat_session(id1,id2,status) VALUES(3,4,'ACTIVE')"));
        jdbc.update("UPDATE chat_session SET status='CLOSED' WHERE id1=3 AND status='ACTIVE'");
        jdbc.update("INSERT INTO chat_session(id1,id2,status) VALUES(3,4,'ACTIVE')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_session WHERE id1=3", Integer.class)).isEqualTo(3);
    }
}
