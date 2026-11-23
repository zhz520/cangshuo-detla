package com.delta.admin.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantStartup;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@Order(-100)
@RequiredArgsConstructor
public class GovernanceSchema implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    @Autowired(required = false)
    private TenantRegistry tenantRegistry;
    @Override public void run(ApplicationArguments args) throws Exception {
        TenantStartup.forEachActive(tenantRegistry, this::initializeCurrentTenant);
    }

    private void initializeCurrentTenant() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            ClassPathResource governance = new ClassPathResource("db/governance-v1.sql");
            ScriptUtils.executeSqlScript(connection, governance);
            recordScript(governance, "governance-v1.sql", "STARTUP");
            boolean reviewNote = false;
            for (String table : java.util.List.of("privacy_request", "PRIVACY_REQUEST")) {
                try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
                    while (columns.next()) {
                        if ("review_note".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) reviewNote = true;
                    }
                }
            }
            if (!reviewNote) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE privacy_request ADD COLUMN review_note VARCHAR(500)");
                }
            }
            ClassPathResource workbench = new ClassPathResource("db/workbench.sql");
            ScriptUtils.executeSqlScript(connection, workbench);
            recordScript(workbench, "workbench.sql", "STARTUP");
        }
        // Inserts only. Existing role and customer settings are never overwritten on startup.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM staff_role WHERE code='ADMIN'",Long.class)==0)
            jdbc.update("INSERT INTO staff_role(code,name,permissions,builtin) VALUES ('ADMIN','管理员',?,1)","[\"*\",\"order.refund\"]");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM staff_role WHERE code='CS'",Long.class)==0)
            jdbc.update("INSERT INTO staff_role(code,name,permissions,builtin) VALUES ('CS','客服',?,1)","[\"order.read\",\"order.manage\",\"player.manage\",\"media.review\",\"chat.manage\",\"user.read\"]");
    }

    private void recordScript(ClassPathResource resource, String id, String source) throws Exception {
        byte[] bytes;
        try (var input = resource.getInputStream()) { bytes = input.readAllBytes(); }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbc.update("INSERT INTO schema_migrations(id,checksum,source) VALUES(?,?,?) " +
                        "ON DUPLICATE KEY UPDATE checksum=VALUES(checksum),source=VALUES(source),applied_at=CURRENT_TIMESTAMP",
                id, checksum, source);
    }
}
