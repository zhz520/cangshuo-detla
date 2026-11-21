package com.delta.workforce.controller;

import com.delta.workforce.controller.CsRoutingController;
import com.delta.common.customer.CsRoutingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CsRoutingBatchRuleTest {
    private JdbcTemplate jdbc;
    private CsRoutingController controller;
    private CsRoutingService routing;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:csbatch_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE admin(id BIGINT PRIMARY KEY,username VARCHAR(32),nickname VARCHAR(32),role VARCHAR(16),status INT,deleted INT)");
        jdbc.execute("CREATE TABLE product(id BIGINT PRIMARY KEY,name VARCHAR(64),category_id BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE category(id BIGINT PRIMARY KEY,name VARCHAR(64),parent_id BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE cs_group(id BIGINT PRIMARY KEY,name VARCHAR(64),enabled INT)");
        jdbc.execute("CREATE TABLE cs_routing_rule(id BIGINT AUTO_INCREMENT PRIMARY KEY,match_type VARCHAR(16)," +
                "target_id BIGINT,assignee_type VARCHAR(16),assignee_id BIGINT,enabled INT,remark VARCHAR(255)," +
                "created_by BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE(match_type,target_id))");
        jdbc.update("INSERT INTO admin VALUES(1,'cs1','客服一','CS',1,0),(2,'cs2','客服二','CS',1,0)");
        jdbc.update("INSERT INTO category VALUES(10,'大分类',0,0),(11,'小分类',10,0),(12,'另一类',0,0)");
        jdbc.update("INSERT INTO product VALUES(1,'测试商品',11,0)");
        routing = new CsRoutingService(jdbc);
        controller = new CsRoutingController(jdbc, routing);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 1L, "userType", "ADMIN"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void batchCreatesRulesForParentAndMultipleChildCategories() {
        var result = controller.saveRules(new CsRoutingController.BatchRuleRequest(
                "CATEGORY", List.of(10L, 11L, 12L), "CS", 2L, 1, "多选分类"));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_routing_rule", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_routing_rule WHERE assignee_id=2", Integer.class))
                .isEqualTo(3);
        assertThat(routing.resolveForProduct(1L)).isEqualTo(2L);
    }

    @Test
    void existingChildOverrideRemainsAndParentRuleIsUsedForUnassignedChildren() {
        controller.saveRule(new CsRoutingController.RuleRequest(null, "CATEGORY", 11L, "CS", 1L, 1, "独立归属"));
        jdbc.update("INSERT INTO category VALUES(13,'另一个子分类',10,0)");
        jdbc.update("INSERT INTO product VALUES(2,'另一商品',13,0)");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.saveRules(
                        new CsRoutingController.BatchRuleRequest("CATEGORY", List.of(10L, 11L), "CS", 2L, 1, "")))
                .isInstanceOf(com.delta.common.exception.BusinessException.class)
                .hasMessageContaining("已有分配规则");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_routing_rule", Integer.class)).isEqualTo(1);

        controller.saveRules(new CsRoutingController.BatchRuleRequest(
                "CATEGORY", List.of(10L, 12L), "CS", 2L, 1, ""));
        assertThat(routing.resolveForProduct(1L)).isEqualTo(1L);
        assertThat(routing.resolveForProduct(2L)).isEqualTo(2L);
    }

    @Test
    void emptyCategorySelectionIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.saveRules(
                        new CsRoutingController.BatchRuleRequest("CATEGORY", List.of(), "CS", 2L, 1, "")))
                .isInstanceOf(com.delta.common.exception.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_routing_rule", Integer.class)).isZero();
    }
}
