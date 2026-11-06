package com.delta.admin.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

@Component
@Order(-100)
@RequiredArgsConstructor
public class GovernanceSchema implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    @Override public void run(ApplicationArguments args) throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/governance-v1.sql"));
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/workbench.sql"));
        }
        // Inserts only. Existing role and customer settings are never overwritten on startup.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM staff_role WHERE code='ADMIN'",Long.class)==0)
            jdbc.update("INSERT INTO staff_role(code,name,permissions,builtin) VALUES ('ADMIN','管理员',?,1)","[\"*\",\"order.refund\"]");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM staff_role WHERE code='CS'",Long.class)==0)
            jdbc.update("INSERT INTO staff_role(code,name,permissions,builtin) VALUES ('CS','客服',?,1)","[\"order.read\",\"order.manage\",\"player.manage\",\"media.review\",\"chat.manage\",\"user.read\"]");
    }
}
