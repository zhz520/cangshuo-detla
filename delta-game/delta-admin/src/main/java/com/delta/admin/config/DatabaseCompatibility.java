package com.delta.admin.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

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

    @PostConstruct
    public void ensure() {
        try {
            ensureColumn(
                    "player_level",
                    "withdraw_deposit_amount",
                    "ALTER TABLE player_level ADD COLUMN withdraw_deposit_amount DECIMAL(10,2) NOT NULL DEFAULT 0");
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
