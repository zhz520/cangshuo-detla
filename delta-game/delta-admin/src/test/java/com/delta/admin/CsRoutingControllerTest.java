package com.delta.admin;

import com.delta.admin.controller.CsRoutingController;
import com.delta.common.customer.CsRoutingService;
import com.delta.common.exception.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsRoutingControllerTest {
    private JdbcTemplate jdbc;
    private CsRoutingService routing;
    private CsRoutingController controller;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:csrouting_controller_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ORDER",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE admin(id BIGINT PRIMARY KEY,username VARCHAR(64),nickname VARCHAR(64),role VARCHAR(16),status INT,deleted INT)");
        jdbc.execute("CREATE TABLE staff_shift(admin_id BIGINT PRIMARY KEY,online INT)");
        jdbc.execute("CREATE TABLE category(id BIGINT PRIMARY KEY,name VARCHAR(64),parent_id BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE product(id BIGINT PRIMARY KEY,name VARCHAR(64),category_id BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE cs_group(id BIGINT PRIMARY KEY,name VARCHAR(64),leader_id BIGINT,enabled INT,remark VARCHAR(255),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE cs_group_member(group_id BIGINT,admin_id BIGINT)");
        jdbc.execute("CREATE TABLE cs_routing_rule(id BIGINT AUTO_INCREMENT PRIMARY KEY,rule_group_id VARCHAR(64)," +
                "match_type VARCHAR(16),target_id BIGINT,assignee_type VARCHAR(16),assignee_id BIGINT,enabled INT," +
                "remark VARCHAR(255),created_by BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,cs_id BIGINT,status VARCHAR(32))");

        jdbc.update("INSERT INTO admin VALUES(1,'admin','平台管理员','ADMIN',1,0),(5,'cs-a','白班客服','CS',1,0)");
        jdbc.update("INSERT INTO staff_shift VALUES(5,1)");
        jdbc.update("INSERT INTO cs_group VALUES(100,'白班组',5,1,'',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO cs_group_member VALUES(100,5)");
        jdbc.update("INSERT INTO category VALUES(20,'射击游戏',30,0),(21,'角色扮演',30,0),(30,'游戏分类',0,0)");
        jdbc.update("INSERT INTO product VALUES(10,'A 商品',20,0),(11,'B 商品',30,0)");

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 1L, "userType", "ADMIN"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        routing = new CsRoutingService(jdbc);
        controller = new CsRoutingController(jdbc, routing);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void oneRuleCanContainParentAndChildCategoriesAndRouteAllOfThem() {
        String groupId = controller.saveRule(new CsRoutingController.RuleRequest(
                null, "CATEGORY", null, List.of(20L, 21L, 30L), "GROUP", 100L, 1, "A 类")).getData();

        List<Map<String, Object>> rules = controller.rules().getData();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("id")).isEqualTo(groupId);
        List<Long> targetIds = ((List<?>) rules.get(0).get("targetIds")).stream()
                .map(Number.class::cast).map(Number::longValue).toList();
        assertThat(targetIds).containsExactly(20L, 21L, 30L);
        assertThat(routing.resolveForProduct(10L)).isEqualTo(5L);
        assertThat(routing.resolveForProduct(11L)).isEqualTo(5L);
    }

    @Test
    void editingRuleReplacesItsTargetSetAndOverlappingTargetIsRejected() {
        String groupId = controller.saveRule(new CsRoutingController.RuleRequest(
                null, "CATEGORY", null, List.of(20L, 21L), "GROUP", 100L, 1, "")).getData();
        controller.saveRule(new CsRoutingController.RuleRequest(
                groupId, "CATEGORY", null, List.of(20L), "GROUP", 100L, 1, "收缩范围"));

        List<Map<String, Object>> rules = controller.rules().getData();
        List<Long> targetIds = ((List<?>) rules.get(0).get("targetIds")).stream()
                .map(Number.class::cast).map(Number::longValue).toList();
        assertThat(targetIds).containsExactly(20L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_routing_rule WHERE target_id=21", Integer.class)).isZero();

        assertThatThrownBy(() -> controller.saveRule(new CsRoutingController.RuleRequest(
                null, "CATEGORY", null, List.of(20L), "CS", 5L, 1, "冲突规则")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(409));
    }
}
