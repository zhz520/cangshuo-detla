package com.delta.governance.controller;

import com.delta.governance.controller.DisplayController;
import com.delta.governance.service.DisplayService;
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
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,nickname VARCHAR(64),avatar VARCHAR(512),is_guest INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT,product_name VARCHAR(128),status VARCHAR(32),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE payment(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32),amount DECIMAL(12,2),refund_amount DECIMAL(12,2),paid_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE display_privacy(user_id BIGINT PRIMARY KEY,spend_rank_visible INT,purchase_feed_consent INT,updated_at TIMESTAMP)");
        configs = mock(SysConfigService.class);
        controller = new DisplayController(new DisplayService(jdbc, configs));
    }

    @Test
    void rankingMasksNamesAndUsesNetPaidAmount() {
        jdbc.update("INSERT INTO `user`(id,nickname,avatar,is_guest) VALUES(1,'小明','',0),(2,'小红','',0)");
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

    @Test
    void rankingHonorsTheUsersOptOut() {
        jdbc.update("INSERT INTO `user`(id,nickname,avatar,is_guest) VALUES(1,'小明','',0),(2,'小红','',0)");
        jdbc.update("INSERT INTO `order` VALUES(10,1,'陪玩','PAID',CURRENT_TIMESTAMP),(11,2,'陪玩','PAID',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO payment VALUES(1,10,'PAID',100,0,CURRENT_TIMESTAMP),(2,11,'PAID',50,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO display_privacy VALUES(1,0,0,CURRENT_TIMESTAMP)");
        when(configs.getConfigValue("display.spend_rank.enabled", "false")).thenReturn("true");

        var items = (java.util.List<?>) controller.spending(20).getData().get("items");

        assertThat(items).hasSize(1);
        assertThat(((java.util.Map<?,?>) items.get(0)).get("nickname")).isEqualTo("小**");
    }

    @Test
    void purchaseFeedRequiresExplicitConsent() {
        jdbc.update("INSERT INTO `user`(id,nickname,avatar,is_guest) VALUES(1,'小明','',0),(2,'小红','private-avatar',0)");
        jdbc.update("INSERT INTO `order` VALUES(10,1,'陪玩','PAID',CURRENT_TIMESTAMP),(11,2,'陪玩','PAID',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO display_privacy VALUES(2,1,1,CURRENT_TIMESTAMP)");
        when(configs.getConfigValue("display.purchase_feed.enabled", "false")).thenReturn("true");

        var items = (java.util.List<?>) controller.feed(20).getData().get("items");

        assertThat(items).hasSize(1);
        var publicRow = (java.util.Map<?,?>) items.get(0);
        assertThat(publicRow.keySet().stream().map(String::valueOf)).containsExactlyInAnyOrder("nickname", "product_name");
        assertThat(publicRow.get("nickname")).isEqualTo("小**");
        jdbc.update("UPDATE display_privacy SET purchase_feed_consent=0 WHERE user_id=2");
        assertThat((java.util.List<?>) controller.feed(20).getData().get("items")).isEmpty();
    }
}
