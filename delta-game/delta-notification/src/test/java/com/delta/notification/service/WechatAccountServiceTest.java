package com.delta.notification.service;

import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.SecretCipher;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.delta.platform.PlatformService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.Mockito;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class WechatAccountServiceTest {
    private JdbcTemplate jdbc;
    private WechatAccountService service;
    private PlatformService platform;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wechat_accounts;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS wechat_mp_template");
        jdbc.execute("DROP TABLE IF EXISTS wechat_mp_template_catalog");
        jdbc.execute("DROP TABLE IF EXISTS wechat_mp_account");
        jdbc.execute("CREATE TABLE wechat_mp_account(id VARCHAR(36) PRIMARY KEY,name VARCHAR(80) NOT NULL," +
                "app_id VARCHAR(64) NOT NULL UNIQUE,app_secret_encrypted VARCHAR(512),callback_token_encrypted VARCHAR(512)," +
                "aes_key_encrypted VARCHAR(512),account_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT',is_default TINYINT NOT NULL DEFAULT 0," +
                "enabled TINYINT NOT NULL DEFAULT 1,last_checked_at TIMESTAMP NULL,last_error VARCHAR(160)," +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE wechat_mp_template_catalog(account_id VARCHAR(36) NOT NULL,template_id VARCHAR(128) NOT NULL," +
                "title VARCHAR(160) NOT NULL,content TEXT,primary_industry VARCHAR(80),deputy_industry VARCHAR(80)," +
                "synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(account_id,template_id))");
        jdbc.execute("CREATE TABLE wechat_mp_template(id BIGINT AUTO_INCREMENT PRIMARY KEY,account_id VARCHAR(36) NOT NULL," +
                "role VARCHAR(16) NOT NULL,scenario VARCHAR(32) NOT NULL,template_id VARCHAR(128) NOT NULL,fields_json TEXT NOT NULL," +
                "url VARCHAR(2000),miniprogram_json VARCHAR(1000),source VARCHAR(16) NOT NULL DEFAULT 'LOCAL',enabled TINYINT NOT NULL DEFAULT 1," +
                "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(account_id,role,scenario))");
        platform = Mockito.mock(PlatformService.class);
        service = new WechatAccountService(jdbc, new SecretCipher("test-only-wechat-encryption-key-32-bytes"), platform,
                Mockito.mock(WechatOpenPlatformClient.class));
    }

    @Test
    void accountSecretsAreEncryptedAndNeverReturnedByList() {
        var saved = service.saveAccount(Map.of(
                "name", "沧炼服务号", "appId", "wx1234567890abcdef", "appSecret", "super-secret-value",
                "defaultAccount", true));

        assertThat(saved).containsEntry("secretConfigured", true).doesNotContainKey("appSecret");
        assertThat(jdbc.queryForObject("SELECT app_secret_encrypted FROM wechat_mp_account", String.class))
                .startsWith("v1:").doesNotContain("super-secret-value");
    }

    @Test
    void templateMappingsArePerRoleAndReadinessTracksMissingScenarios() {
        String accountId = String.valueOf(service.saveAccount(Map.of(
                "name", "沧炼服务号", "appId", "wx1234567890abcdef", "appSecret", "super-secret-value",
                "defaultAccount", true)).get("id"));

        service.saveTemplate(accountId, Map.of(
                "role", "ADMIN", "scenario", "new_order", "templateId", "admin-new-order",
                "fields", Map.of("thing1", "message")));

        assertThat(service.templateFor("ADMIN", "new_order").getString("templateId"))
                .isEqualTo("admin-new-order");
        assertThat(service.missingScenarios("ADMIN")).doesNotContain("new_order").contains("order_status");
        var readiness = service.readiness(accountId);
        assertThat(readiness).containsEntry("secretConfigured", true).containsEntry("ready", false);
    }

    @Test
    void invalidTemplateFieldsAreRejectedBeforePersistence() {
        String accountId = String.valueOf(service.saveAccount(Map.of(
                "name", "沧炼服务号", "appId", "wx1234567890abcdef", "appSecret", "super-secret-value",
                "defaultAccount", true)).get("id"));

        assertThatThrownBy(() -> service.saveTemplate(accountId, Map.of(
                "role", "ADMIN", "scenario", "new_order", "templateId", "admin-new-order",
                "fields", Map.of("bad_field", "message"))))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
    }

    @Test
    void authoritySchemePrefillsMappingsButNeverSuppliesATemplateId() throws Exception {
        when(platform.projectSnapshot()).thenReturn(new ObjectMapper().readTree("""
                {"wechatTemplates":{"version":1,"schemes":[{"role":"ADMIN","scenario":"new_order",
                "name":"管理员 · 新订单","templateHint":"新订单待处理","fields":{"thing1":"message"},"version":2}]}}
                """));
        String accountId = String.valueOf(service.saveAccount(Map.of(
                "name", "沧炼服务号", "appId", "wx1234567890abcdef", "appSecret", "super-secret-value",
                "defaultAccount", true)).get("id"));

        assertThat(service.authoritySchemes()).hasSize(1);
        service.saveTemplate(accountId, Map.of(
                "role", "ADMIN", "scenario", "new_order", "templateId", "admin-new-order", "fields", Map.of()));

        assertThat(service.templateFor("ADMIN", "new_order").getString("templateId")).isEqualTo("admin-new-order");
        assertThat(service.templateFor("ADMIN", "new_order").getJSONObject("fields").getString("thing1")).isEqualTo("message");
    }

    @Test
    void verifiedImportIsIdempotentAndNeverReplacesTheDirectLoginAccount() {
        String original = String.valueOf(service.saveAccount(Map.of("name","直连号","appId","wx1234567890abcdef",
                "appSecret","fixture-secret","defaultAccount",true)).get("id"));
        var open = service.importAuthorizedAccount("wx0000000000000001","扫码号");
        assertThat(open).containsEntry("mode","OPEN_PLATFORM").containsEntry("defaultAccount",false)
                .doesNotContainKeys("appSecret","accessToken","refreshToken");
        assertThat(service.importAuthorizedAccount("wx0000000000000001","扫码号").get("id")).isEqualTo(open.get("id"));
        assertThat(service.importAuthorizedAccount("wx1234567890abcdef","扫码同一公众号"))
                .containsEntry("id",original).containsEntry("mode","DIRECT");
        assertThat(service.accounts()).hasSize(2);
        assertThat(service.defaultAppId()).isEqualTo("wx1234567890abcdef");
        assertThatThrownBy(() -> service.saveAccount(Map.of("name","手工","appId","wx0000000000000002","mode","OPEN_PLATFORM")))
                .isInstanceOf(BusinessException.class);
        service.deleteAccount(String.valueOf(open.get("id")));
        assertThat(service.defaultAppId()).isEqualTo("wx1234567890abcdef");
    }

    @Test
    void managedTemplateMustBelongToSelectedAccountAndUseItsActualFields() {
        String first = service.importAuthorizedAccount("wx0000000000000001", "第一公众号").get("id").toString();
        String second = service.importAuthorizedAccount("wx0000000000000002", "第二公众号").get("id").toString();
        jdbc.update("INSERT INTO wechat_mp_template_catalog(account_id,template_id,title,content) VALUES(?,?,?,?)",
                first, "actual-template", "订单通知", "状态：{{thing3.DATA}}\n时间：{{time7.DATA}}");
        Map<String, Object> mapping = Map.of("role", "ADMIN", "scenario", "new_order", "templateId", "actual-template",
                "fields", Map.of("thing3", "status", "time7", "time"));
        assertThatThrownBy(() -> service.saveTemplate(second, mapping))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        assertThatThrownBy(() -> service.saveTemplate(first, Map.of("role", "ADMIN", "scenario", "new_order",
                "templateId", "actual-template", "fields", Map.of("thing1", "status"))))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        service.saveTemplate(first, mapping);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wechat_mp_template WHERE account_id=?", Integer.class, first))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wechat_mp_template WHERE account_id=?", Integer.class, second))
                .isZero();
    }

    @Test
    void theSameDatabaseCannotReuseAnAppId() {
        service.saveAccount(Map.of("name", "A", "appId", "wx1234567890abcdef", "appSecret", "secret-a"));

        assertThatThrownBy(() -> service.saveAccount(Map.of(
                "name", "B", "appId", "wx1234567890abcdef", "appSecret", "secret-b")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
    }
}
