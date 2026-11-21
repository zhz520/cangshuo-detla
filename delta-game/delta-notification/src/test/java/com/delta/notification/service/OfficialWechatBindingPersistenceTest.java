package com.delta.notification.service;

import com.delta.platform.PlatformService;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.service.StaffPermissions;
import com.delta.system.service.SysConfigService;
import com.delta.notification.service.NotificationTemplateDataService;
import com.delta.notification.service.OfficialWechatService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialWechatBindingPersistenceTest {
    private JdbcTemplate jdbc;
    private SysConfigService configs;
    private PlatformService platform;
    private OfficialWechatService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wechat_binding;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS wechat_official_binding");
        jdbc.execute("CREATE TABLE wechat_official_binding(" +
                "user_type VARCHAR(16) NOT NULL,user_id BIGINT NOT NULL,openid VARCHAR(128) NOT NULL," +
                "PRIMARY KEY(user_type,user_id),UNIQUE(openid))");
        configs = Mockito.mock(SysConfigService.class);
        platform = Mockito.mock(PlatformService.class);
        service = new OfficialWechatService(
                jdbc,
                configs,
                new NotificationTemplateDataService(jdbc),
                Mockito.mock(StaffPermissions.class),
                platform,
                Mockito.mock(StringRedisTemplate.class));
    }

    @Test
    void oneWechatCanOnlyBindOneAccountAcrossAllRoles() {
        service.saveBinding("USER", 7L, "openid-shared-123");

        assertThatThrownBy(() -> service.saveBinding("ADMIN", 8L, "openid-shared-123"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getMessage()).contains("已绑定其他账号");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wechat_official_binding", Long.class)).isEqualTo(1L);
    }

    @Test
    void adminAndCsKeepTheirOwnIdentityAndCanReplaceTheirWechat() {
        service.saveBinding("ADMIN", 11L, "openid-admin-123");
        service.saveBinding("CS", 22L, "openid-cs-1234");

        assertThat(jdbc.queryForList(
                "SELECT user_type FROM wechat_official_binding ORDER BY user_id", String.class))
                .containsExactly("ADMIN", "CS");

        service.saveBinding("ADMIN", 11L, "openid-admin-456");

        assertThat(jdbc.queryForObject(
                "SELECT openid FROM wechat_official_binding WHERE user_type='ADMIN' AND user_id=11", String.class))
                .isEqualTo("openid-admin-456");
    }

    @Test
    void legacyBothRoleIsStoredAsAdminForNotificationRouting() {
        assertThat(OfficialWechatService.canonicalUserType("both")).isEqualTo("ADMIN");

        service.saveBinding("BOTH", 33L, "openid-legacy-123");

        assertThat(jdbc.queryForObject(
                "SELECT user_type FROM wechat_official_binding WHERE user_id=33", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void bindingStatusListsMissingRoleTemplatesWithoutBlockingBinding() {
        service.saveBinding("ADMIN", 11L, "openid-admin-123");
        Mockito.when(platform.featureEnabled(OfficialWechatService.FEATURE_ID)).thenReturn(true);
        Mockito.when(configs.getConfigValue("wechat.official.enabled", "false")).thenReturn("true");

        var status = service.binding("ADMIN", 11L);

        assertThat(status).containsEntry("bound", true).containsEntry("templateReady", false);
        assertThat((List<String>) status.get("missingTemplateGroups"))
                .containsExactly("new_order", "order_status", "refund_status", "complaint_status", "staff_review");
    }
}
