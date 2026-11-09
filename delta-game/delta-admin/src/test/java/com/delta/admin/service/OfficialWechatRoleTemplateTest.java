package com.delta.admin.service;

import com.alibaba.fastjson2.JSONObject;
import com.delta.admin.platform.PlatformService;
import com.delta.common.security.service.StaffPermissions;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OfficialWechatRoleTemplateTest {
    @Mock JdbcTemplate jdbc;
    @Mock SysConfigService configs;
    @Mock StaffPermissions permissions;
    @Mock PlatformService platform;
    @Mock StringRedisTemplate redis;

    private OfficialWechatService service;

    @BeforeEach
    void setUp() {
        service = new OfficialWechatService(jdbc, configs, new NotificationTemplateDataService(jdbc), permissions, platform, redis);
    }

    @Test
    void customerPlayerAdminAndCsResolveDifferentTemplateIds() {
        when(configs.getConfigValue("wechat.official.templates.user", "{}"))
                .thenReturn("{\"new_order\":{\"templateId\":\"user-order\",\"fields\":{\"thing1\":\"message\"}}}");
        when(configs.getConfigValue("wechat.official.templates.player", "{}"))
                .thenReturn("{\"new_order\":{\"templateId\":\"player-order\",\"fields\":{\"thing1\":\"message\"}}}");
        when(configs.getConfigValue("wechat.official.templates.admin", "{}"))
                .thenReturn("{\"new_order\":{\"templateId\":\"admin-order\",\"fields\":{\"thing1\":\"message\"}}}");
        when(configs.getConfigValue("wechat.official.templates.cs", "{}"))
                .thenReturn("{\"new_order\":{\"templateId\":\"cs-order\",\"fields\":{\"thing1\":\"message\"}}}");

        assertThat(service.templateFor("USER", "new_order").getString("templateId")).isEqualTo("user-order");
        assertThat(service.templateFor("PLAYER", "new_order").getString("templateId")).isEqualTo("player-order");
        assertThat(service.templateFor("ADMIN", "new_order").getString("templateId")).isEqualTo("admin-order");
        assertThat(service.templateFor("CS", "new_order").getString("templateId")).isEqualTo("cs-order");
        assertThat(service.templateFor("BOTH", "new_order").getString("templateId")).isEqualTo("admin-order");
    }

    @Test
    void legacyTemplateStillWorksWhenARoleHasNoDedicatedMapping() {
        when(configs.getConfigValue("wechat.official.templates.user", "{}")).thenReturn("{}");
        when(configs.getConfigValue("wechat.official.template.new_order", "{}"))
                .thenReturn("{\"templateId\":\"legacy-order\",\"fields\":{\"thing1\":\"message\"}}");

        JSONObject template = service.templateFor("USER", "new_order");

        assertThat(template.getString("templateId")).isEqualTo("legacy-order");
    }

    @Test
    void officialTemplateFieldsUseTheirCurrentLengthLimits() {
        assertThat(OfficialWechatService.maxFieldLength("thing1")).isEqualTo(20);
        assertThat(OfficialWechatService.maxFieldLength("character_string2")).isEqualTo(32);
        assertThat(OfficialWechatService.maxFieldLength("time6")).isEqualTo(64);
        assertThat(OfficialWechatService.maxFieldLength("amount3")).isEqualTo(20);
        assertThat(OfficialWechatService.maxFieldLength("phone_number1")).isEqualTo(17);
        assertThat(OfficialWechatService.maxFieldLength("car_number1")).isEqualTo(8);
        assertThat(OfficialWechatService.maxFieldLength("const1")).isEqualTo(20);
        assertThat(OfficialWechatService.maxFieldLength("phrase1")).isEqualTo(5);
        assertThat(OfficialWechatService.amountText("68.00")).isEqualTo("￥68.00");
        assertThat(OfficialWechatService.templateFieldValue("amount3", "orderAmount", "68.00")).isEqualTo("￥68.00");
        assertThat(OfficialWechatService.templateFieldValue("thing1", "orderAmount", "68.00")).isEqualTo("68.00");
        assertThat(OfficialWechatService.fieldValue("thing1", "第一行\n第二行\t")).isEqualTo("第一行 第二行");
        assertThat(OfficialWechatService.fieldValue("thing1", "123456789012345678901")).isEqualTo("12345678901234567890");
    }

    @Test
    void templateLinkOnlyAcceptsHttpOrHttps() {
        assertThat(OfficialWechatService.templateUrl("https://jgs.kk520yy.com/order/1")).isEqualTo("https://jgs.kk520yy.com/order/1");
        assertThat(OfficialWechatService.templateUrl(" ")).isNull();
        assertThatThrownBy(() -> OfficialWechatService.templateUrl("javascript:alert(1)"))
                .hasMessage("TEMPLATE_CONFIG_INVALID");
        assertThatThrownBy(() -> OfficialWechatService.templateUrl("https://user:pass@example.com/path"))
                .hasMessage("TEMPLATE_CONFIG_INVALID");
    }

    @Test
    void sendPayloadUsesTheCurrentOfficialRequestShape() {
        JSONObject mini = JSONObject.parseObject("{\"appid\":\"wx1234567890\",\"pagepath\":\"pages/order/detail?id=1\"}");
        var payload = OfficialWechatService.messagePayload(
                "openid-1", "template-1", Map.of("thing1", Map.of("value", "新订单")),
                "https://jgs.kk520yy.com/order/1", mini, "cangshuo-queue-1");

        assertThat(payload).containsEntry("touser", "openid-1")
                .containsEntry("template_id", "template-1")
                .containsEntry("url", "https://jgs.kk520yy.com/order/1")
                .containsEntry("client_msg_id", "cangshuo-queue-1")
                .containsKey("miniprogram");
    }

}
