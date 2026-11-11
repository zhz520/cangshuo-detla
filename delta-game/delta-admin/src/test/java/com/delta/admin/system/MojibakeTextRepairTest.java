package com.delta.admin.system;

import com.delta.system.support.MojibakeTextRepair;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MojibakeTextRepairTest {
    @Test
    void repairsTheTwoGroupsObservedInProduction() {
        assertEquals("公众号通知", MojibakeTextRepair.repair(fromHex(
                "C3A5E280A6C2ACC3A4C2BCE28094C3A5C28FC2B7C3A9E282ACC5A1C3A7C5B8C2A5")));
        assertEquals("站点配置", MojibakeTextRepair.repair(fromHex(
                "C3A7C2ABE284A2C3A7E2809AC2B9C3A9E280A6C28DC3A7C2BDC2AE")));
    }

    @Test
    void leavesNormalChineseAndAsciiUntouched() {
        assertEquals("公众号通知", MojibakeTextRepair.repair("公众号通知"));
        assertEquals("管理员与客服使用的 HTTPS 后台 Origin",
                MojibakeTextRepair.repair("管理员与客服使用的 HTTPS 后台 Origin"));
        assertEquals("https://chuanapi742.idcgov.cn",
                MojibakeTextRepair.repair("https://chuanapi742.idcgov.cn"));
        assertNull(MojibakeTextRepair.repair(null));
    }

    private static String fromHex(String hex) {
        return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }
}
