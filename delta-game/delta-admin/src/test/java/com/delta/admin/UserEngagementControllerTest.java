package com.delta.admin;

import com.delta.common.domain.PageQuery;
import com.delta.user.controller.UserEngagementController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserEngagementControllerTest {
    private JdbcTemplate jdbc;
    private UserEngagementController controller;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:favorite" + System.nanoTime() +
                ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE product(id BIGINT PRIMARY KEY,name VARCHAR(100),subtitle VARCHAR(100),cover_image VARCHAR(255),price DECIMAL(10,2),status INT,sales_count INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE player(id BIGINT PRIMARY KEY,nickname VARCHAR(100),avatar VARCHAR(255),level INT,introduction VARCHAR(500),cover_image VARCHAR(255),avg_rating DECIMAL(3,2),order_count INT,complete_rate DECIMAL(5,2),is_online INT,status VARCHAR(20),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE player_level(id INT PRIMARY KEY,name VARCHAR(50),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE user_favorite(user_id BIGINT,product_id BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(user_id,product_id))");
        jdbc.execute("CREATE TABLE player_follow(user_id BIGINT,player_id BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(user_id,player_id))");
        jdbc.update("INSERT INTO product VALUES(1,'测试商品','说明','/file/p.jpg',50,1,0,0)");
        jdbc.update("INSERT INTO player VALUES(2,'测试打手','/file/a.jpg',1,'简介','/file/c.jpg',4.8,10,98,1,'ACTIVE',0)");
        jdbc.update("INSERT INTO player_level VALUES(1,'普通',0)");
        controller = new UserEngagementController(jdbc);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 7L, "userType", "USER"), null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void favoriteAndFollowAreIdempotentAndCanBeRemoved() {
        controller.favorite(1L);
        controller.favorite(1L);
        assertThat(controller.favoriteStatus(1L).getData().get("favorited")).isEqualTo(true);
        assertThat(controller.favorites(page()).getData().getTotal()).isEqualTo(1);
        controller.unfavorite(1L);
        assertThat(controller.favoriteStatus(1L).getData().get("favorited")).isEqualTo(false);

        controller.follow(2L);
        controller.follow(2L);
        assertThat(controller.followStatus(2L).getData().get("following")).isEqualTo(true);
        assertThat(controller.follows(page()).getData().getTotal()).isEqualTo(1);
        controller.unfollow(2L);
        assertThat(controller.followStatus(2L).getData().get("following")).isEqualTo(false);
    }

    private PageQuery page() {
        var page = new PageQuery();
        page.setPageNum(1);
        page.setPageSize(20);
        return page;
    }
}
