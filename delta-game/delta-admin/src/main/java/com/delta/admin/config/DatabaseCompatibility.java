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
            for (AdditiveColumn required : REQUIRED_COLUMNS) {
                ensureColumn(required.table(), required.column(), required.ddl());
            }
        } catch (SQLException error) {
            throw new IllegalStateException("数据库结构兼容检查失败", error);
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
