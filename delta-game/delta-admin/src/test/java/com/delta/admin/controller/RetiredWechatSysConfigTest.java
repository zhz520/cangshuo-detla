package com.delta.admin.controller;

import com.delta.common.exception.BusinessException;
import com.delta.common.exception.GlobalExceptionHandler;
import com.delta.common.redis.service.RedisService;
import com.delta.system.controller.SysConfigController;
import com.delta.system.entity.SysConfig;
import com.delta.system.service.SysConfigService;
import com.delta.system.service.impl.SysConfigServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = RetiredWechatSysConfigTest.Config.class, properties = {
        "spring.profiles.active=test", "spring.datasource.url=jdbc:h2:mem:retired_wechat_config;MODE=MySQL",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@Sql(statements = "CREATE TABLE IF NOT EXISTS sys_config(id BIGINT AUTO_INCREMENT PRIMARY KEY, config_key VARCHAR(128) UNIQUE, config_name VARCHAR(128), config_value TEXT, value_type VARCHAR(32), config_group VARCHAR(64), remark VARCHAR(512), created_at TIMESTAMP, updated_at TIMESTAMP)")
class RetiredWechatSysConfigTest {
    @Configuration
    @EnableAutoConfiguration(excludeName = "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @MapperScan("com.delta.system.mapper")
    @Import({SysConfigController.class, SysConfigServiceImpl.class, GlobalExceptionHandler.class})
    static class Config {}

    private static final String EVENT = "wechat.official.template.new_order";
    private static final String ROLE = "wechat.official.templates.admin";
    private static final String ENABLED = "wechat.official.enabled";
    @Autowired MockMvc api;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired SysConfigService service;
    @MockBean RedisService redis;

    private void seed(String key, String value) {
        db.update("INSERT INTO sys_config(config_key,config_value) VALUES(?,?)", key, value);
    }
    private Long id(String key) {
        return db.queryForObject("SELECT id FROM sys_config WHERE config_key=?", Long.class, key);
    }
    private String raw(String key) {
        return db.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?", String.class, key);
    }
    private void putConfig(String path, Object body, int expected) throws Exception {
        api.perform(put("/system/config" + path).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(jsonPath("$.code").value(expected));
    }

    @Test void listNeitherPublishesNorReseedsRetiredRowsAndStillMasksTheOfficialSecret() throws Exception {
        seed(EVENT, "historical-event"); seed(ROLE, "historical-role");
        seed("wechat.official.app_secret", "fixture-only");
        String result = api.perform(get("/system/config/list")).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result).doesNotContain(EVENT, ROLE, "historical-event", "historical-role", "fixture-only")
                .contains(ENABLED, "wechat.official.app_id", "wechat.official.app_secret", "********",
                        "wechat.official.redirect_url", "wechat.official.staff_redirect_url", "auth.wechat.enabled");
        assertThat(db.queryForList("SELECT config_key FROM sys_config WHERE config_key LIKE 'wechat.official.template.%' OR config_key LIKE 'wechat.official.templates.%'", String.class))
                .containsExactlyInAnyOrder(EVENT, ROLE);
        assertThat(raw(EVENT)).isEqualTo("historical-event");
        assertThat(raw(ROLE)).isEqualTo("historical-role");
        putConfig("", Map.of("id", id(ENABLED), "configValue", "true"), 200);
        putConfig("/batch", List.of(Map.of("id", id("wechat.official.app_id"), "configValue", "fixture-app"),
                Map.of("id", id("wechat.official.app_secret"), "configValue", "********"),
                Map.of("id", id("auth.wechat.enabled"), "configValue", "true"),
                Map.of("id", id("wechat.official.redirect_url"), "configValue", "https://user.example.test/"),
                Map.of("id", id("wechat.official.staff_redirect_url"), "configValue", "https://staff.example.test/h5/wechat-binding")), 200);
        assertThat(raw(ENABLED)).isEqualTo("true");
        assertThat(raw("wechat.official.app_id")).isEqualTo("fixture-app");
        assertThat(raw("wechat.official.app_secret")).isEqualTo("fixture-only");
        assertThat(raw("auth.wechat.enabled")).isEqualTo("true");
        assertThat(raw("wechat.official.redirect_url")).isEqualTo("https://user.example.test/");
        assertThat(raw("wechat.official.staff_redirect_url")).isEqualTo("https://staff.example.test/h5/wechat-binding");
    }

    @Test void singleAndMixedBatchRejectRetiredIdsRegardlessOfSubmittedKey() throws Exception {
        seed(EVENT, "old-event"); seed(ROLE, "old-role"); seed(ENABLED, "auto");
        putConfig("", Map.of("id", id(EVENT), "configKey", ENABLED, "configValue", "{}"), 409);
        putConfig("/batch", List.of(Map.of("id", id(ENABLED), "configValue", "false"),
                Map.of("id", id(ROLE), "configValue", "{}")), 409);
        assertThat(raw(EVENT)).isEqualTo("old-event");
        assertThat(raw(ROLE)).isEqualTo("old-role");
        assertThat(raw(ENABLED)).isEqualTo("auto");
    }

    @Test void configServiceIgnoresCachedLegacyValuesAndRejectsRecreationOrRenaming() {
        seed(ROLE, "original"); seed(ENABLED, "auto");
        for (String key : List.of(ROLE, EVENT, "wechat.official.templates.future_role")) {
            assertThat(service.getConfigValue(key)).isNull();
            assertThat(service.getConfigValue(key, "{}")).isEqualTo("{}");
        }
        verifyNoInteractions(redis);
        SysConfig newRow = new SysConfig(); newRow.setConfigKey(EVENT);
        assertThatThrownBy(() -> service.save(newRow)).isInstanceOf(BusinessException.class);
        SysConfig spoofed = new SysConfig(); spoofed.setId(id(ROLE)); spoofed.setConfigKey(ENABLED);
        assertThatThrownBy(() -> service.updateById(spoofed)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.updateBatchById(List.of(spoofed))).isInstanceOf(BusinessException.class);
        assertThat(raw(ROLE)).isEqualTo("original");
        assertThat(service.getConfigValue(ENABLED)).isEqualTo("auto");
    }
}
