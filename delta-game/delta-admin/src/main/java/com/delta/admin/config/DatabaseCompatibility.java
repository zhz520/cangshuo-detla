package com.delta.admin.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantStartup;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Additive schema upgrades required by the running backend.
 *
 * <p>The host updater replaces application containers but intentionally does not run
 * arbitrary migration files. These checks run before the web server accepts traffic,
 * so a missed additive migration cannot expose an otherwise healthy API that returns
 * 500 responses for a missing column.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DatabaseCompatibility {
    private final JdbcTemplate jdbc;
    @Autowired(required = false)
    private TenantRegistry tenantRegistry;

    private record AdditiveColumn(String table, String column, String ddl) {}

    private static final List<AdditiveColumn> REQUIRED_COLUMNS = List.of(
            new AdditiveColumn("identity_verification", "privacy_version",
                    "ALTER TABLE identity_verification ADD COLUMN privacy_version VARCHAR(32) NOT NULL DEFAULT ''"),
            new AdditiveColumn("identity_verification", "privacy_accepted_at",
                    "ALTER TABLE identity_verification ADD COLUMN privacy_accepted_at TIMESTAMP NULL"),
            new AdditiveColumn("player_level", "grab_captcha_required",
                    "ALTER TABLE player_level ADD COLUMN grab_captcha_required TINYINT NOT NULL DEFAULT 1"),
            new AdditiveColumn("player_level", "price_increase_rate",
                    "ALTER TABLE player_level ADD COLUMN price_increase_rate DECIMAL(5,2) NOT NULL DEFAULT 0"),
            new AdditiveColumn("player_level", "withdraw_deposit_amount",
                    "ALTER TABLE player_level ADD COLUMN withdraw_deposit_amount DECIMAL(10,2) NOT NULL DEFAULT 0"),
            new AdditiveColumn("player_wallet", "pending_income",
                    "ALTER TABLE player_wallet ADD COLUMN pending_income DECIMAL(12,2) NOT NULL DEFAULT 0"),
            new AdditiveColumn("player", "level",
                    "ALTER TABLE player ADD COLUMN level INT NOT NULL DEFAULT 1"),
            new AdditiveColumn("player", "introduction",
                    "ALTER TABLE player ADD COLUMN introduction VARCHAR(1000) NOT NULL DEFAULT ''"),
            new AdditiveColumn("player", "cover_image",
                    "ALTER TABLE player ADD COLUMN cover_image VARCHAR(512) NOT NULL DEFAULT ''"),
            new AdditiveColumn("player", "album_images",
                    "ALTER TABLE player ADD COLUMN album_images TEXT"),
            new AdditiveColumn("player", "story",
                    "ALTER TABLE player ADD COLUMN story TEXT"),
            new AdditiveColumn("player", "voice_url",
                    "ALTER TABLE player ADD COLUMN voice_url VARCHAR(512) NOT NULL DEFAULT ''"),
            new AdditiveColumn("player", "voice_duration",
                    "ALTER TABLE player ADD COLUMN voice_duration INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("player", "grab_captcha_override",
                    "ALTER TABLE player ADD COLUMN grab_captcha_override TINYINT NOT NULL DEFAULT -1"),
            new AdditiveColumn("player", "price_increase_rate",
                    "ALTER TABLE player ADD COLUMN price_increase_rate DECIMAL(5,2) NOT NULL DEFAULT -1"),
            new AdditiveColumn("order", "base_amount",
                    "ALTER TABLE `order` ADD COLUMN base_amount DECIMAL(10,2) DEFAULT NULL"),
            new AdditiveColumn("order", "price_increase_rate",
                    "ALTER TABLE `order` ADD COLUMN price_increase_rate DECIMAL(5,2) DEFAULT NULL"),
            new AdditiveColumn("order", "price_increase_amount",
                    "ALTER TABLE `order` ADD COLUMN price_increase_amount DECIMAL(10,2) DEFAULT NULL"),
            new AdditiveColumn("order", "member_tier",
                    "ALTER TABLE `order` ADD COLUMN member_tier VARCHAR(32) DEFAULT NULL"),
            new AdditiveColumn("order", "member_discount_rate",
                    "ALTER TABLE `order` ADD COLUMN member_discount_rate DECIMAL(5,4) DEFAULT NULL"),
            new AdditiveColumn("order", "member_discount_amount",
                    "ALTER TABLE `order` ADD COLUMN member_discount_amount DECIMAL(10,2) DEFAULT NULL"),
            new AdditiveColumn("order", "coupon_id",
                    "ALTER TABLE `order` ADD COLUMN coupon_id BIGINT DEFAULT NULL"),
            new AdditiveColumn("order", "coupon_name",
                    "ALTER TABLE `order` ADD COLUMN coupon_name VARCHAR(80) DEFAULT NULL"),
            new AdditiveColumn("order", "coupon_discount_amount",
                    "ALTER TABLE `order` ADD COLUMN coupon_discount_amount DECIMAL(10,2) DEFAULT NULL"),
            new AdditiveColumn("order", "requested_player_level_id",
                    "ALTER TABLE `order` ADD COLUMN requested_player_level_id INT DEFAULT NULL"),
            new AdditiveColumn("order", "player_level_name",
                    "ALTER TABLE `order` ADD COLUMN player_level_name VARCHAR(30) DEFAULT NULL"),
            new AdditiveColumn("user", "password",
                    "ALTER TABLE `user` ADD COLUMN password VARCHAR(100) DEFAULT NULL"),
            new AdditiveColumn("user", "auth_version",
                    "ALTER TABLE `user` ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0"),
            new AdditiveColumn("user", "is_guest",
                    "ALTER TABLE `user` ADD COLUMN is_guest TINYINT NOT NULL DEFAULT 0"),
            new AdditiveColumn("payment", "wx_app_id",
                    "ALTER TABLE payment ADD COLUMN wx_app_id VARCHAR(64) DEFAULT NULL"),
            new AdditiveColumn("wechat_notice_queue", "dedupe_key",
                    "ALTER TABLE wechat_notice_queue ADD COLUMN dedupe_key VARCHAR(64) NULL")
    );

    /** The daily statistics table has existed under more than one column naming scheme. */
    private static final List<AdditiveColumn> STATISTICS_COLUMNS = List.of(
            new AdditiveColumn("statistics_daily", "new_user_count",
                    "ALTER TABLE statistics_daily ADD COLUMN new_user_count INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "active_user_count",
                    "ALTER TABLE statistics_daily ADD COLUMN active_user_count INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "new_order_count",
                    "ALTER TABLE statistics_daily ADD COLUMN new_order_count INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "completed_order_count",
                    "ALTER TABLE statistics_daily ADD COLUMN completed_order_count INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "cancelled_order_count",
                    "ALTER TABLE statistics_daily ADD COLUMN cancelled_order_count INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "total_amount",
                    "ALTER TABLE statistics_daily ADD COLUMN total_amount DECIMAL(12,2) NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "platform_income",
                    "ALTER TABLE statistics_daily ADD COLUMN platform_income DECIMAL(12,2) NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "player_income",
                    "ALTER TABLE statistics_daily ADD COLUMN player_income DECIMAL(12,2) NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "new_player_count",
                    "ALTER TABLE statistics_daily ADD COLUMN new_player_count INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "active_player_count",
                    "ALTER TABLE statistics_daily ADD COLUMN active_player_count INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "new_complaint_count",
                    "ALTER TABLE statistics_daily ADD COLUMN new_complaint_count INT NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "avg_order_amount",
                    "ALTER TABLE statistics_daily ADD COLUMN avg_order_amount DECIMAL(10,2) NOT NULL DEFAULT 0"),
            new AdditiveColumn("statistics_daily", "created_at",
                    "ALTER TABLE statistics_daily ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP")
    );

    @PostConstruct
    public void ensure() {
        TenantStartup.forEachActive(tenantRegistry, this::ensureCurrentTenant);
    }

    private void ensureCurrentTenant() {
        try {
            ensureTable("identity_verification", "CREATE TABLE IF NOT EXISTS identity_verification (" +
                    "user_id BIGINT PRIMARY KEY, real_name VARCHAR(64) NOT NULL DEFAULT '', id_number_hash CHAR(64) NULL, " +
                    "id_number_masked VARCHAR(32) NOT NULL DEFAULT '', front_image LONGTEXT NULL, front_mime VARCHAR(64) NULL, " +
                    "back_image LONGTEXT NULL, back_mime VARCHAR(64) NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', " +
                    "privacy_version VARCHAR(32) NOT NULL DEFAULT '', privacy_accepted_at TIMESTAMP NULL, " +
                    "review_note VARCHAR(500) NOT NULL DEFAULT '', reviewer_id BIGINT NULL, submitted_at TIMESTAMP NULL, " +
                    "reviewed_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "UNIQUE KEY uk_identity_document(id_number_hash))");
            ensureTable("email_notice_queue", "CREATE TABLE IF NOT EXISTS email_notice_queue (" +
                    "id VARCHAR(36) PRIMARY KEY, event_type VARCHAR(64) NOT NULL, event_hash CHAR(64) NOT NULL, " +
                    "recipient VARCHAR(254) NOT NULL, subject VARCHAR(255) NOT NULL, plain_body TEXT NOT NULL, " +
                    "html_body TEXT NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING', " +
                    "attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(100), " +
                    "next_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, sent_at TIMESTAMP NULL, " +
                    "CONSTRAINT uk_email_notice_event_recipient UNIQUE(event_hash, recipient), " +
                    "INDEX idx_email_notice_delivery(status,next_at))");
            ensureTable("player_reward", "CREATE TABLE IF NOT EXISTS player_reward (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, reward_no VARCHAR(40) NOT NULL, " +
                    "request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, " +
                    "user_id BIGINT NOT NULL, player_id BIGINT NOT NULL, order_id BIGINT NOT NULL, " +
                    "order_no VARCHAR(64) NOT NULL, amount DECIMAL(12,2) NOT NULL, fee DECIMAL(12,2) NOT NULL, " +
                    "income DECIMAL(12,2) NOT NULL, fee_percent DECIMAL(5,2) NOT NULL, " +
                    "user_balance_before DECIMAL(12,2) NOT NULL, user_balance_after DECIMAL(12,2) NOT NULL, " +
                    "player_balance_before DECIMAL(12,2) NOT NULL, player_balance_after DECIMAL(12,2) NOT NULL, " +
                    "reversal_user_balance_before DECIMAL(12,2), reversal_user_balance_after DECIMAL(12,2), " +
                    "reversal_player_balance_before DECIMAL(12,2), reversal_player_balance_after DECIMAL(12,2), " +
                    "payment_method VARCHAR(16) NOT NULL DEFAULT 'BALANCE', remark VARCHAR(200), " +
                    "status VARCHAR(24) NOT NULL, pending_recovery DECIMAL(12,2) NOT NULL DEFAULT 0, " +
                    "reversal_reason VARCHAR(200), reversal_admin_id BIGINT, reversed_at TIMESTAMP NULL, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "UNIQUE KEY uk_reward_no(reward_no), UNIQUE KEY uk_order_reward(order_id), " +
                    "UNIQUE KEY uk_user_reward_request(user_id,request_id), " +
                    "KEY idx_reward_user_date(user_id,created_at), KEY idx_reward_player_date(player_id,created_at), " +
                    "KEY idx_reward_status(status))");
            ensureTable("guest_checkout_profile", "CREATE TABLE IF NOT EXISTS guest_checkout_profile (" +
                    "user_id BIGINT PRIMARY KEY, phone_hash CHAR(64) NOT NULL, phone_mask VARCHAR(20) NOT NULL, " +
                    "lookup_hash CHAR(64) NOT NULL, privacy_version VARCHAR(32) NOT NULL DEFAULT '', " +
                    "privacy_accepted_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "UNIQUE KEY uk_guest_phone(phone_hash))");
            ensureTable("guest_user_link", "CREATE TABLE IF NOT EXISTS guest_user_link (" +
                    "guest_user_id BIGINT PRIMARY KEY, registered_user_id BIGINT NOT NULL, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "KEY idx_guest_link_registered(registered_user_id))");
            ensureTable("player_income_settlement", "CREATE TABLE IF NOT EXISTS player_income_settlement (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, player_id BIGINT NOT NULL, " +
                    "role VARCHAR(20) NOT NULL DEFAULT 'PRIMARY', amount DECIMAL(12,2) NOT NULL, " +
                    "reversed_amount DECIMAL(12,2) NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', " +
                    "release_at DATETIME NOT NULL, released_at DATETIME NULL, attempts INT NOT NULL DEFAULT 0, " +
                    "last_error VARCHAR(255) NULL, next_attempt_at DATETIME NOT NULL, remark VARCHAR(255) NOT NULL DEFAULT '', " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "UNIQUE KEY uk_income_settlement_order_player(order_id,player_id), " +
                    "KEY idx_income_settlement_due(status,next_attempt_at), KEY idx_income_settlement_player(player_id,status))");
            ensureTable("user_favorite", "CREATE TABLE IF NOT EXISTS user_favorite (" +
                    "user_id BIGINT NOT NULL, product_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY(user_id,product_id), KEY idx_user_favorite_product(product_id))");
            ensureTable("player_follow", "CREATE TABLE IF NOT EXISTS player_follow (" +
                    "user_id BIGINT NOT NULL, player_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY(user_id,player_id), KEY idx_player_follow_player(player_id))");
            ensureTable("refund_request", "CREATE TABLE IF NOT EXISTS refund_request (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, refund_no VARCHAR(40) NOT NULL, " +
                    "order_id BIGINT NOT NULL, order_no VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, " +
                    "payment_id BIGINT NULL, payment_method VARCHAR(32) NULL, amount DECIMAL(12,2) NOT NULL, " +
                    "reason VARCHAR(500) NOT NULL, status VARCHAR(32) NOT NULL, order_status_before VARCHAR(32) NOT NULL, " +
                    "requester_type VARCHAR(16) NOT NULL, requester_id BIGINT NOT NULL, client_request_id VARCHAR(64) NULL, " +
                    "cs_reviewer_id BIGINT NULL, cs_recommendation VARCHAR(16) NULL, cs_note VARCHAR(500) NULL, " +
                    "admin_id BIGINT NULL, admin_decision VARCHAR(16) NULL, admin_note VARCHAR(500) NULL, " +
                    "source_type VARCHAR(32) NULL, source_id BIGINT NULL, channel_refund_no VARCHAR(64) NULL, " +
                    "last_error VARCHAR(500) NULL, active_order_id BIGINT NULL, " +
                    "submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, cs_reviewed_at TIMESTAMP NULL, " +
                    "admin_decided_at TIMESTAMP NULL, processing_at TIMESTAMP NULL, completed_at TIMESTAMP NULL, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "UNIQUE KEY uk_refund_request_no(refund_no), " +
                    "UNIQUE KEY uk_refund_request_active_order(active_order_id), " +
                    "UNIQUE KEY uk_refund_request_client(requester_type,requester_id,client_request_id), " +
                    "KEY idx_refund_request_order(order_id,created_at), " +
                    "KEY idx_refund_request_status(status,created_at), " +
                    "KEY idx_refund_request_source(source_type,source_id))");
            ensureTable("refund_request_audit", "CREATE TABLE IF NOT EXISTS refund_request_audit (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, refund_request_id BIGINT NOT NULL, " +
                    "action VARCHAR(40) NOT NULL, from_status VARCHAR(32) NULL, to_status VARCHAR(32) NULL, " +
                    "operator_type VARCHAR(16) NOT NULL, operator_id BIGINT NULL, note VARCHAR(500) NULL, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "KEY idx_refund_request_audit_request(refund_request_id,created_at))");
            ensureTable("wechat_official_binding", "CREATE TABLE IF NOT EXISTS wechat_official_binding (" +
                    "user_type VARCHAR(16) NOT NULL, user_id BIGINT NOT NULL, openid VARCHAR(128) NOT NULL, " +
                    "PRIMARY KEY(user_type,user_id), UNIQUE KEY uk_wechat_official_openid(openid))");
            ensureTable("wechat_official_binding_conflict_backup", "CREATE TABLE IF NOT EXISTS wechat_official_binding_conflict_backup (" +
                    "user_type VARCHAR(16) NOT NULL, user_id BIGINT NOT NULL, openid VARCHAR(128) NOT NULL, " +
                    "archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_type,user_id))");
            ensureTable("sms_notice_queue", "CREATE TABLE IF NOT EXISTS sms_notice_queue (" +
                    "id VARCHAR(36) PRIMARY KEY, dedupe_key CHAR(64) NOT NULL, event_type VARCHAR(64) NOT NULL, " +
                    "event_group VARCHAR(32) NOT NULL, user_type VARCHAR(16) NOT NULL, user_id BIGINT NOT NULL, " +
                    "order_id BIGINT NULL, phone VARCHAR(32) NOT NULL, message VARCHAR(1000) NOT NULL, " +
                    "status VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, " +
                    "last_error VARCHAR(100) NULL, next_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, sent_at TIMESTAMP NULL, " +
                    "UNIQUE KEY uk_sms_notice_dedupe(dedupe_key), KEY idx_sms_notice_delivery(status,next_at))");
            ensureTable("analytics_daily_visitor", "CREATE TABLE IF NOT EXISTS analytics_daily_visitor (" +
                    "visit_date DATE NOT NULL, visitor_key CHAR(64) NOT NULL, device_hash CHAR(64) NOT NULL, " +
                    "user_type VARCHAR(16) NULL, user_id BIGINT NULL, " +
                    "first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, hits BIGINT NOT NULL DEFAULT 1, " +
                    "PRIMARY KEY(visit_date,visitor_key), " +
                    "KEY idx_analytics_daily_visitor_user(visit_date,user_type,user_id), " +
                    "KEY idx_analytics_daily_visitor_last_seen(last_seen_at))");
            ensureTable("coupon_template", "CREATE TABLE IF NOT EXISTS coupon_template (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(80) NOT NULL,type VARCHAR(16) NOT NULL," +
                    "coupon_value DECIMAL(12,2) NOT NULL,min_spend DECIMAL(12,2) NOT NULL DEFAULT 0,valid_days INT NOT NULL," +
                    "total INT NOT NULL DEFAULT 0,claimed INT NOT NULL DEFAULT 0,per_user_limit INT NOT NULL DEFAULT 1," +
                    "status TINYINT NOT NULL DEFAULT 1,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            ensureTable("user_coupon", "CREATE TABLE IF NOT EXISTS user_coupon (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,template_id BIGINT NOT NULL,user_id BIGINT NOT NULL," +
                    "status VARCHAR(16) NOT NULL,claimed_at TIMESTAMP NOT NULL,expires_at TIMESTAMP NOT NULL," +
                    "used_order_id BIGINT,used_at TIMESTAMP," +
                    "KEY idx_user_coupon_user_template(user_id,template_id),KEY idx_user_coupon_user_status(user_id,status))");
            ensureTable("invite_code", "CREATE TABLE IF NOT EXISTS invite_code (" +
                    "user_id BIGINT PRIMARY KEY,code VARCHAR(16) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uk_invite_code(code))");
            ensureTable("invite_relation", "CREATE TABLE IF NOT EXISTS invite_relation (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,inviter_id BIGINT NOT NULL,invitee_id BIGINT NOT NULL," +
                    "code VARCHAR(16) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uk_invite_invitee(invitee_id))");
            ensureTable("invite_reward", "CREATE TABLE IF NOT EXISTS invite_reward (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,inviter_id BIGINT NOT NULL,invitee_id BIGINT NOT NULL," +
                    "order_id BIGINT NOT NULL,amount DECIMAL(12,2) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING'," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uk_invite_reward_invitee(invitee_id))");
            ensureTable("invite_reward_debt", "CREATE TABLE IF NOT EXISTS invite_reward_debt (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,reward_id BIGINT NOT NULL,inviter_id BIGINT NOT NULL," +
                    "amount DECIMAL(12,2) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING'," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,recovered_at TIMESTAMP NULL," +
                    "UNIQUE KEY uk_invite_reward_debt(reward_id))");
            ensureTable("display_privacy", "CREATE TABLE IF NOT EXISTS display_privacy (" +
                    "user_id BIGINT PRIMARY KEY,spend_rank_visible TINYINT NOT NULL DEFAULT 1," +
                    "purchase_feed_consent TINYINT NOT NULL DEFAULT 0," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
            ensureTable("statistics_daily", "CREATE TABLE IF NOT EXISTS statistics_daily (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,stat_date DATE NOT NULL,new_user_count INT NOT NULL DEFAULT 0," +
                    "active_user_count INT NOT NULL DEFAULT 0,new_order_count INT NOT NULL DEFAULT 0," +
                    "completed_order_count INT NOT NULL DEFAULT 0,cancelled_order_count INT NOT NULL DEFAULT 0," +
                    "total_amount DECIMAL(12,2) NOT NULL DEFAULT 0,platform_income DECIMAL(12,2) NOT NULL DEFAULT 0," +
                    "player_income DECIMAL(12,2) NOT NULL DEFAULT 0,new_player_count INT NOT NULL DEFAULT 0," +
                    "active_player_count INT NOT NULL DEFAULT 0,new_complaint_count INT NOT NULL DEFAULT 0," +
                    "avg_order_amount DECIMAL(10,2) NOT NULL DEFAULT 0,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uk_stat_date(stat_date))");
            for (AdditiveColumn required : REQUIRED_COLUMNS) {
                ensureColumn(required.table(), required.column(), required.ddl());
            }
            for (AdditiveColumn required : STATISTICS_COLUMNS) {
                ensureColumn(required.table(), required.column(), required.ddl());
            }
            ensureOfficialWechatBindingUniqueness();
            ensureIndex("wechat_notice_queue", "uk_wechat_notice_dedupe",
                    "CREATE UNIQUE INDEX uk_wechat_notice_dedupe ON wechat_notice_queue(dedupe_key)");
            ensureIndex("wechat_notice_queue", "idx_wechat_notice_delivery",
                    "CREATE INDEX idx_wechat_notice_delivery ON wechat_notice_queue(status,next_at)");
            removeLegacyCouponUniqueIndex();
            ensureRewardGrabConfig();
            ensureGuestCheckoutConfig();
            ensureSettlementConfig();
        } catch (SQLException error) {
            throw new IllegalStateException("数据库结构兼容检查失败", error);
        }
    }

    private void ensureTable(String table, String ddl) throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            if (findTable(connection, table) == null) jdbc.execute(ddl);
        }
    }

    private void ensureColumn(String table, String column, String ddl) throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String actualTable = findTable(connection, table);
            if (actualTable == null || hasColumn(connection, actualTable, column)) return;
            jdbc.execute(ddl);
        }
    }

    private void ensureIndex(String table, String index, String ddl) throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String actualTable = findTable(connection, table);
            if (actualTable == null || hasIndex(connection, actualTable, index)) return;
            try {
                jdbc.execute(ddl);
            } catch (RuntimeException error) {
                // Another application replica may have created the same index concurrently.
                try (Connection retry = jdbc.getDataSource().getConnection()) {
                    String retryTable = findTable(retry, table);
                    if (retryTable != null && hasIndex(retry, retryTable, index)) return;
                }
                throw error;
            }
        }
    }

    /** Older installations used a unique (template,user) key, which made per_user_limit > 1
     * impossible. The user_coupon row itself is the claim ticket, so multiple rows are valid. */
    private void removeLegacyCouponUniqueIndex() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String actualTable = findTable(connection, "user_coupon");
            if (actualTable == null || !hasIndex(connection, actualTable, "uk_coupon_user_template")) return;
            if (connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")) {
                jdbc.execute("DROP INDEX `uk_coupon_user_template` ON `" + actualTable + "`");
            } else {
                jdbc.execute("DROP INDEX uk_coupon_user_template");
            }
        }
    }

    private void ensureOfficialWechatBindingUniqueness() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String actualTable = findTable(connection, "wechat_official_binding");
            if (actualTable == null || hasIndex(connection, actualTable, "uk_wechat_official_openid")) return;
        }
        Integer conflicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT openid FROM wechat_official_binding GROUP BY openid HAVING COUNT(*)>1) duplicate_openid",
                Integer.class);
        if (conflicts != null && conflicts > 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT b.user_type,b.user_id,b.openid FROM wechat_official_binding b " +
                            "JOIN (SELECT openid FROM wechat_official_binding GROUP BY openid HAVING COUNT(*)>1) d " +
                            "ON d.openid=b.openid");
            for (Map<String, Object> row : rows) {
                if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wechat_official_binding_conflict_backup WHERE user_type=? AND user_id=?",
                        Integer.class, row.get("user_type"), row.get("user_id")) == 0) {
                    jdbc.update("INSERT INTO wechat_official_binding_conflict_backup(user_type,user_id,openid) VALUES (?,?,?)",
                            row.get("user_type"), row.get("user_id"), row.get("openid"));
                }
            }
            log.error("公众号绑定存在 {} 个重复 OpenID；冲突数据已备份，唯一索引暂未创建，请人工确认保留账号", conflicts);
            return;
        }
        ensureIndex("wechat_official_binding", "uk_wechat_official_openid",
                "CREATE UNIQUE INDEX uk_wechat_official_openid ON wechat_official_binding(openid)");
    }

    private void ensureRewardGrabConfig() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            if (findTable(connection, "sys_config") == null) return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_config WHERE config_key='features.reward_grab'", Integer.class);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark) " +
                        "VALUES(?,?,?,?,?,?)",
                "features.reward_grab",
                "打赏与抢单验证规则",
                "{\"rewardEnabled\":false,\"minAmount\":1,\"maxAmount\":200,\"validDays\":30," +
                        "\"userDailyLimit\":10,\"playerDailyLimit\":5,\"pairDailyLimit\":5,\"feePercent\":0," +
                        "\"captchaSeconds\":120,\"captchaAttempts\":5,\"captchaRefreshSeconds\":10}",
                "json",
                "reward-grab",
                "在打赏与抢单设置页面修改");
    }

    private void ensureGuestCheckoutConfig() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            if (findTable(connection, "sys_config") == null) return;
        }
        insertBooleanConfigIfMissing("order.purchase_enabled", "启用前台购买", "true",
                "关闭后前台隐藏全部立即购买和立即点单按钮，并禁止创建新订单；已有订单仍可支付和处理");
        insertBooleanConfigIfMissing("order.guest_checkout_enabled", "允许游客下单", "false",
                "开启后未登录用户可用手机号和查单码创建游客订单；游客订单使用独立影子账户，不写入正式会员统计");
        insertBooleanConfigIfMissing("order.cooperation.enabled", "启用协同打单", "true",
                "关闭后不再接受新的队友邀请；已经确认的队伍仍可继续履约、退款和结算");
        insertBooleanConfigIfMissing("auth.phone.enabled", "启用手机验证码登录", "true",
                "关闭后网页端不再提供手机验证码登录；密码辅助入口和微信登录分别受独立开关控制");
        insertBooleanConfigIfMissing("auth.password.enabled", "启用密码辅助登录", "true",
                "关闭后网页端不显示密码登录入口；已设置密码的账号仍可通过其他已启用方式登录");
        insertBooleanConfigIfMissing("auth.wechat.enabled", "启用网页微信登录", "false",
                "开启后还需配置公众号 AppID 和 AppSecret；仅影响网页登录，不改变公众号通知绑定");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key='auth.primary_method'", Integer.class) == 0)
            jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark) VALUES(?,?,?,?,?,?)",
                    "auth.primary_method", "网页主登录方式", "PHONE", "text", "账号与登录",
                    "可选 PHONE 或 WECHAT；微信未完成配置时客户端自动回退可用登录方式");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key='site.customer_web_url'", Integer.class) == 0)
            jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark) VALUES(?,?,?,?,?,?)",
                    "site.customer_web_url", "客户 H5 站点地址", "", "text", "站点配置",
                    "微信网页登录完成后的客户 H5 HTTPS Origin，不含路径、查询参数和片段");
        for (String[] item : new String[][]{
                {"identity.auto.enabled","启用自动二要素实名","false","boolean","实名配置","开启后仍受授权中心服务余额和实名策略约束"},
                {"identity.prompt_mode","实名提醒方式","OFF","text","实名配置","OFF/REMIND/REQUIRED；REQUIRED 只限制配置指定的业务动作"},
                {"membership.enabled","启用用户会员等级","false","boolean","会员配置","关闭后停止新增会员权益，既有等级和历史记录保留"},
                {"membership.tiers","会员等级规则",com.delta.common.membership.MembershipTierRules.DEFAULT_TIERS_JSON,"json","会员配置","按有效净消费成长；minSpend 单位元，discountRate 为0到1之间的折扣率"},
                {"marketing.coupon.issue_enabled","启用优惠券发放","false","boolean","营销配置","关闭后停止领券和新发券，已发有效券按原规则处理"},
                {"marketing.coupon.redeem_enabled","启用优惠券核销","true","boolean","营销配置","紧急停用开关；关闭后新订单不能使用优惠券"},
                {"marketing.invite.enabled","启用邀请奖励","false","boolean","营销配置","停止新邀请归因和新奖励，已满足资格的奖励继续履约"},
                {"marketing.invite.reward_amount","邀请首单奖励金额","10","number","营销配置","被邀请人首个合格订单确认后记录的奖励金额，单位元"},
                {"display.spend_rank.enabled","启用消费排行","false","boolean","展示配置","关闭后隐藏榜单及公开接口，基础财务数据保留"},
                {"display.purchase_feed.enabled","启用最近购买动态","false","boolean","展示配置","关闭后停止公开展示，不停止订单和支付处理"}}) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key=?", Integer.class, item[0]) == 0)
                jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark) VALUES(?,?,?,?,?,?)",
                        item[0], item[1], item[2], item[3], item[4], item[5]);
        }
        insertBooleanConfigIfMissing("player.directory.enabled", "展示打手页面", "true",
                "关闭后隐藏用户端底部打手入口，并阻止直接打开打手列表或详情页面");
    }

    private void ensureSettlementConfig() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            if (findTable(connection, "sys_config") == null) return;
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key='settlement.hold_hours'", Integer.class) == 0) {
            jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark) VALUES(?,?,?,?,?,?)",
                    "settlement.hold_hours", "订单收益售后观察期", "72", "number", "结算配置",
                    "订单确认后先进入待结算收益，观察期结束且无投诉、退款时转为可提现余额；单位小时，0至720");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key='withdraw.require_identity_match'", Integer.class) == 0) {
            jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark) VALUES(?,?,?,?,?,?)",
                    "withdraw.require_identity_match", "提现校验收款人实名", "true", "boolean", "提现配置",
                    "开启后收款账户姓名必须与已通过实名或入驻真实姓名一致，避免换绑他人账户收款");
        }
    }

    private void insertBooleanConfigIfMissing(String key, String name, String value, String remark) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key=?", Integer.class, key);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark) " +
                        "VALUES(?,?,?,?,?,?)",
                key, name, value, "boolean", "订单配置", remark);
    }

    private String findTable(Connection connection, String expected) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", null)) {
            while (tables.next()) {
                String candidate = tables.getString("TABLE_NAME");
                if (expected.equalsIgnoreCase(candidate)) return candidate;
            }
        }
        return null;
    }

    private boolean hasColumn(Connection connection, String table, String expected) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, "%")) {
            while (columns.next()) {
                if (expected.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    private boolean hasIndex(Connection connection, String table, String expected) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                String candidate = indexes.getString("INDEX_NAME");
                if (expected.equalsIgnoreCase(candidate)) return true;
            }
        }
        return false;
    }
}
