package com.delta.admin.system;

import com.delta.system.support.SysConfigMetadataRepair;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SysConfigMetadataRepairTest {
    @Test
    void restoresCanonicalMetadataEvenWhenTheStoredBytesAreIncomplete() {
        String brokenName = "å·¥ä½œäººå‘˜å\u0090Žå\u008f°åœ°å\u009d€";
        String brokenGroup = "ç«™ç‚¹é…\u008dç½®";

        assertEquals("工作人员后台地址", SysConfigMetadataRepair.name("site.staff_web_url", brokenName));
        assertEquals("站点配置", SysConfigMetadataRepair.group("site.staff_web_url", brokenGroup));
        assertEquals("管理员与客服使用的 HTTPS 后台 Origin，例如 https://jgsapi.example.com；客户H5旧工作人员入口会跳转到这里",
                SysConfigMetadataRepair.remark("site.staff_web_url", "broken"));
    }

    @Test
    void restoresThe742NotificationGroup() {
        assertEquals("公众号通知", SysConfigMetadataRepair.group(
                "wechat.official.staff_redirect_url", "å…¬ä¼—å\u008f·é€šçŸ¥"));
    }

    @Test
    void keepsGenericRepairForUnknownKeys() {
        assertEquals("公众号通知", SysConfigMetadataRepair.group("custom.key", fromHex(
                "C3A5E280A6C2ACC3A4C2BCE28094C3A5C28FC2B7C3A9E282ACC5A1C3A7C5B8C2A5")));
        assertEquals("正常名称", SysConfigMetadataRepair.name("custom.key", "正常名称"));
    }

    private static String fromHex(String hex) {
        return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }
}
