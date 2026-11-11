package com.delta.system.support;

import java.util.Map;

/** Canonical metadata for built-in config keys that must survive old SQL imports. */
public final class SysConfigMetadataRepair {
    public record Metadata(String name, String group, String remark) {
    }

    private static final Map<String, Metadata> CANONICAL = Map.of(
            "site.staff_web_url", new Metadata(
                    "工作人员后台地址",
                    "站点配置",
                    "管理员与客服使用的 HTTPS 后台 Origin，例如 https://jgsapi.example.com；客户H5旧工作人员入口会跳转到这里"),
            "wechat.official.staff_redirect_url", new Metadata(
                    "管理员与客服绑定回调页",
                    "公众号通知",
                    "工作人员后台的HTTPS绑定页，例如 https://staff.example.com/h5/wechat-binding；旧 /mobile/wechat-binding 配置会自动改写；不含#和查询参数，并在微信后台配置该后台域名为网页授权域名")
    );

    private SysConfigMetadataRepair() {
    }

    public static String name(String key, String value) {
        Metadata metadata = CANONICAL.get(key);
        return metadata == null ? MojibakeTextRepair.repair(value) : metadata.name();
    }

    public static String group(String key, String value) {
        Metadata metadata = CANONICAL.get(key);
        return metadata == null ? MojibakeTextRepair.repair(value) : metadata.group();
    }

    public static String remark(String key, String value) {
        Metadata metadata = CANONICAL.get(key);
        return metadata == null ? MojibakeTextRepair.repair(value) : metadata.remark();
    }
}
