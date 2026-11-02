package com.delta.admin.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Additive schema upgrades required by the running backend.
 *
 * <p>The host updater replaces application containers but intentionally does not run
 * arbitrary migration files. These checks run before the web server accepts traffic,
 * so a missed additive migration cannot expose an otherwise healthy API that returns
 * 500 responses for a missing column.</p>
 */
@Component
@RequiredArgsConstructor
public class DatabaseCompatibility {
    private final JdbcTemplate jdbc;

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
            new AdditiveColumn("order", "requested_player_level_id",
                    "ALTER TABLE `order` ADD COLUMN requested_player_level_id INT DEFAULT NULL"),
            new AdditiveColumn("order", "player_level_name",
                    "ALTER TABLE `order` ADD COLUMN player_level_name VARCHAR(30) DEFAULT NULL"),
            new AdditiveColumn("user", "password",
                    "ALTER TABLE `user` ADD COLUMN password VARCHAR(100) DEFAULT NULL"),
            new AdditiveColumn("user", "auth_version",
                    "ALTER TABLE `user` ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0")
    );

    @PostConstruct
    public void ensure() {
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
            for (AdditiveColumn required : REQUIRED_COLUMNS) {
                ensureColumn(required.table(), required.column(), required.ddl());
            }
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
}
