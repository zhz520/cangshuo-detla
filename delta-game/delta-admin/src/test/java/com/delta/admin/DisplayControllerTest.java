package com.delta.admin;

import com.delta.admin.controller.DisplayController;
import com.delta.system.service.SysConfigService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisplayControllerTest {
    JdbcTemplate jdbc;
    SysConfigService configs;
    DisplayController controller;

    @BeforeEach
    void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:display-" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,nickname VARCHAR(64),avatar VARCHAR(512),is_guest INT)");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT,product_name VARCHAR(128),status VARCHAR(32),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE payment(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32),amount DECIMAL(12,2),refund_amount DECIMAL(12,2),paid_at TIMESTAMP)");
        configs = mock(SysConfigService.class);
        controller = new DisplayController(jdbc, configs);
    }

    @Test
    void rankingMasksNamesAndUsesNetPaidAmount() {
        jdbc.update("INSERT INTO `user` VALUES(1,'小明','',0),(2,'小红','',0)");
        jdbc.update("INSERT INTO `order` VALUES(10,1,'陪玩','PAID',CURRENT_TIMESTAMP),(11,2,'陪玩','PAID',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO payment VALUES(1,10,'PAID',100,20,CURRENT_TIMESTAMP),(2,11,'PAID',50,0,CURRENT_TIMESTAMP)");
        when(configs.getConfigValue("display.spend_rank.enabled", "false")).thenReturn("true");

        var result = controller.spending(20).getData();

        assertThat(result.get("enabled")).isEqualTo(true);
        assertThat((java.util.List<?>) result.get("items")).hasSize(2);
        assertThat(((java.util.Map<?,?>) ((java.util.List<?>) result.get("items")).get(0)).get("nickname")).isEqualTo("小**");
    }

    @Test
    void disabledFeedReturnsNoData() {
        when(configs.getConfigValue("display.purchase_feed.enabled", "false")).thenReturn("false");
        var result = controller.feed(20).getData();
        assertThat(result.get("enabled")).isEqualTo(false);
        assertThat((java.util.List<?>) result.get("items")).isEmpty();
    }
}
