package com.delta.governance.controller;

import com.delta.governance.controller.DisplayPrivacyController;
import com.delta.governance.service.DisplayPrivacyService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayPrivacyControllerTest {
    JdbcTemplate jdbc;
    DisplayPrivacyController controller;

    @BeforeEach
    void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:display-privacy-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE display_privacy(user_id BIGINT PRIMARY KEY,spend_rank_visible INT,purchase_feed_consent INT,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        controller = new DisplayPrivacyController(new DisplayPrivacyService(jdbc));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 7L, "userType", "USER"), null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void missingRowUsesPrivateByDefaultAndUpdatesOnlyTheProvidedField() {
        var initial = controller.current().getData();
        assertThat(initial.get("spendRankVisible")).isEqualTo(true);
        assertThat(initial.get("purchaseFeedConsent")).isEqualTo(false);

        controller.update(Map.of("purchaseFeedConsent", true));
        var updated = controller.current().getData();
        assertThat(updated.get("spendRankVisible")).isEqualTo(true);
        assertThat(updated.get("purchaseFeedConsent")).isEqualTo(true);
    }
}
