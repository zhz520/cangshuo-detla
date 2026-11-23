package com.delta.notification.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WechatMenuDefinition {
    private static final Set<String> EVENT_TYPES = Set.of("click", "scancode_push", "scancode_waitmsg",
            "pic_sysphoto", "pic_photo_or_album", "pic_weixin", "location_select");

    private WechatMenuDefinition() {}

    static JSONObject normalize(Object raw, boolean allowEmpty) {
        if (!(raw instanceof Map<?, ?> map) || !(map.get("button") instanceof List<?> list) ||
                (!allowEmpty && list.isEmpty()) || list.size() > 3)
            throw new BusinessException(400, "公众号菜单最多 3 个一级菜单，发布时至少配置 1 个");
        JSONArray normalized = new JSONArray();
        for (Object entry : list) normalized.add(button(entry, 0));
        return new JSONObject(Map.of("button", normalized));
    }

    static JSONObject imported(JSONObject response) {
        JSONObject envelope = response.getJSONObject("menu");
        Object mainButtons = (envelope == null ? response : envelope).get("button");
        if (!(mainButtons instanceof List<?> list) || list.isEmpty())
            throw new BusinessException(409, "微信未返回可导入的普通菜单，本地草稿未修改");
        // Unknown vendor actions remain visible for editing. Publishing always
        // requires validation against the current supported action types.
        return new JSONObject(Map.of("button", list));
    }

    private static JSONObject button(Object raw, int depth) {
        if (!(raw instanceof Map<?, ?> row)) throw invalid("菜单格式不正确");
        JSONObject output = new JSONObject();
        output.put("name", required(row, "name", "菜单名称", depth == 0 ? 16 : 60));
        if (row.get("sub_button") != null && !(row.get("sub_button") instanceof List<?>))
            throw invalid("子菜单格式不正确");
        if (row.get("sub_button") instanceof List<?> children && !children.isEmpty()) {
            if (depth != 0 || children.size() > 5) throw invalid("只支持两级菜单，每组最多 5 个子菜单");
            JSONArray sub = new JSONArray();
            for (Object child : children) sub.add(button(child, 1));
            output.put("sub_button", sub);
            return output;
        }
        String type = required(row, "type", "点击动作", 32);
        output.put("type", type);
        if (EVENT_TYPES.contains(type)) {
            output.put("key", required(row, "key", "事件标识", 128));
        } else switch (type) {
            case "view" -> output.put("url", webUrl(required(row, "url", "网页地址", 1024)));
            case "miniprogram" -> {
                String appId = required(row, "appid", "小程序 AppID", 18);
                if (!appId.matches("wx[A-Za-z0-9]{16}")) throw invalid("小程序 AppID 格式不正确");
                output.put("appid", appId);
                output.put("pagepath", required(row, "pagepath", "小程序页面路径", 1024));
                output.put("url", webUrl(required(row, "url", "网页地址", 1024)));
            }
            case "media_id", "view_limited" -> output.put("media_id", required(row, "media_id", "微信素材 ID", 128));
            case "article_id", "article_view_limited" -> output.put("article_id", required(row, "article_id", "文章 ID", 128));
            default -> throw invalid("请选择支持的菜单动作");
        }
        return output;
    }

    private static String required(Map<?, ?> row, String key, String label, int bytes) {
        if (!(row.get(key) instanceof String value) || value.isBlank()) throw invalid("请填写" + label);
        value = value.strip();
        if (value.getBytes(StandardCharsets.UTF_8).length > bytes || value.codePoints().anyMatch(Character::isISOControl))
            throw invalid(label + "格式不正确或超过 " + bytes + " 字节");
        return value;
    }

    private static String webUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) &&
                    uri.getHost() != null && uri.getRawUserInfo() == null) return value;
        } catch (IllegalArgumentException ignored) { }
        throw invalid("网页地址必须为有效的 HTTP 或 HTTPS 链接");
    }

    private static BusinessException invalid(String message) { return new BusinessException(400, message); }
}
