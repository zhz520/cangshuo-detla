package com.delta.admin;

import com.delta.common.customer.CsRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CsRoutingServiceTest {
    private JdbcTemplate jdbc;
    private CsRoutingService routing;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:csrouting_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ORDER",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE admin(id BIGINT PRIMARY KEY,role VARCHAR(16),status INT,deleted INT)");
        jdbc.execute("CREATE TABLE product(id BIGINT PRIMARY KEY,category_id BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE category(id BIGINT PRIMARY KEY,parent_id BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE staff_shift(admin_id BIGINT PRIMARY KEY,online INT)");
        jdbc.execute("CREATE TABLE cs_group(id BIGINT PRIMARY KEY,name VARCHAR(64),enabled INT)");
        jdbc.execute("CREATE TABLE cs_group_member(group_id BIGINT,admin_id BIGINT)");
        jdbc.execute("CREATE TABLE cs_routing_rule(id BIGINT AUTO_INCREMENT PRIMARY KEY,match_type VARCHAR(16)," +
                "target_id BIGINT,assignee_type VARCHAR(16),assignee_id BIGINT,enabled INT)");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,product_id BIGINT,cs_id BIGINT,status VARCHAR(32))");

        jdbc.update("INSERT INTO admin VALUES(1,'CS',1,0),(2,'CS',1,0),(3,'CS',1,0),(4,'ADMIN',1,0),(5,'CS',0,0)");
        jdbc.update("INSERT INTO staff_shift VALUES(1,1),(2,0),(3,1)");
        jdbc.update("INSERT INTO cs_group VALUES(100,'白班组',1),(101,'仅下线组',1)");
        jdbc.update("INSERT INTO cs_group_member VALUES(100,1),(100,2),(100,3),(101,2)");
        jdbc.update("INSERT INTO category VALUES(20,30,0),(30,0,0),(21,0,0),(22,0,0)");
        jdbc.update("INSERT INTO product VALUES(10,20,0),(11,21,0),(12,20,0),(13,22,0),(14,21,0),(15,21,0)");
        jdbc.update("INSERT INTO cs_routing_rule(match_type,target_id,assignee_type,assignee_id,enabled) VALUES" +
                "('PRODUCT',10,'GROUP',100,1),('CATEGORY',30,'CS',2,1),('DEFAULT',0,'CS',1,1)," +
                "('CATEGORY',20,'CS',2,1),('PRODUCT',13,'GROUP',101,1),('PRODUCT',15,'CS',5,1)");
        jdbc.update("INSERT INTO `order` VALUES(1001,10,1,'IN_PROGRESS'),(1002,10,1,'IN_PROGRESS'),(1003,10,3,'IN_PROGRESS')");
        routing = new CsRoutingService(jdbc);
    }

    @Test
    void productRuleWinsAndGroupBalancesAcrossOnDutyMembers() {
        assertThat(routing.resolveForProduct(10L)).isEqualTo(3L);
    }

    @Test
    void nearestCategoryWinsOverParentAndDefault() {
        assertThat(routing.resolveForProduct(12L)).isEqualTo(2L);
    }

    @Test
    void defaultRuleIsUsedWhenNoCategoryMatches() {
        assertThat(routing.resolveForProduct(11L)).isEqualTo(1L);
    }

    @Test
    void groupWithoutOnDutyMemberFallsBackToPublicPoolWhenNoDefaultExists() {
        jdbc.update("UPDATE cs_routing_rule SET enabled=0 WHERE match_type='DEFAULT'");
        assertThat(routing.resolveForProduct(13L)).isNull();
    }

    @Test
    void disabledCsRuleIsSkipped() {
        assertThat(routing.resolveForProduct(15L)).isEqualTo(1L);
    }

    @Test
    void assignedOrderIsVisibleOnlyToItsOwnerOrSharedWhenUnassigned() {
        assertThat(routing.canCsHandleOrder(1001L, 1L)).isTrue();
        assertThat(routing.canCsHandleOrder(1001L, 3L)).isFalse();
        jdbc.update("INSERT INTO `order` VALUES(2000,12,NULL,'PAID')");
        assertThat(routing.canCsHandleOrder(2000L, 3L)).isTrue();
    }
}
