package com.delta.notification.service;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Map;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialWechatRoleTemplateTest {
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
