package com.delta.admin;

import com.delta.common.exception.BusinessException;
import com.delta.player.service.PlayerIdentityNameService;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerIdentityNameServiceTest {
    JdbcTemplate jdbc;
    PlayerIdentityNameService service;

    @BeforeEach void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:player-identity-name;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,openid VARCHAR(64),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE player(id BIGINT PRIMARY KEY,openid VARCHAR(64),real_name VARCHAR(64),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE identity_verification(user_id BIGINT PRIMARY KEY,status VARCHAR(20),real_name VARCHAR(64),reviewed_at TIMESTAMP)");
        jdbc.update("INSERT INTO `user` VALUES(7,'openid-7',0)");
        jdbc.update("INSERT INTO player VALUES(1,'openid-7','入驻姓名',0)");
        SysConfigService config = mock(SysConfigService.class);
        when(config.getConfigValue("withdraw.require_identity_match", "true")).thenReturn("true");
        service = new PlayerIdentityNameService(jdbc, config);
    }

    @Test void approvedIdentityIsNormalizedAndMatchesFullWidthSpaces() {
        jdbc.update("INSERT INTO identity_verification VALUES(7,'APPROVED','张 三',CURRENT_TIMESTAMP)");
        var identity = service.resolve(1L);
        assertThat(identity.name()).isEqualTo("张 三");
        assertThat(identity.approved()).isTrue();
        assertThat(PlayerIdentityNameService.matches(identity.name(), "张三")).isTrue();
        assertThat(PlayerIdentityNameService.matches(identity.name(), "张　三")).isTrue();
    }

    @Test void pendingIdentityDoesNotFallBackToOnboardingName() {
        jdbc.update("INSERT INTO identity_verification VALUES(7,'PENDING','张三',CURRENT_TIMESTAMP)");
        assertThatThrownBy(() -> service.resolve(1L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("实名认证尚未通过");
    }

    @Test void missingIdentityFallsBackToOnboardingName() {
        var identity = service.resolve(1L);
        assertThat(identity.name()).isEqualTo("入驻姓名");
        assertThat(identity.approved()).isFalse();
    }
}
