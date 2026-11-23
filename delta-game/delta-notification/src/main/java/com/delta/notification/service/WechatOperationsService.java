package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Basic official-account operations. Only account-scoped data is stored here;
 * followers and messages are never treated as Delta customer identities.
 */
@Service
@RequiredArgsConstructor
public class WechatOperationsService {
    private final JdbcTemplate jdbc;
    private final WechatAccountService accounts;

    public Map<String, Object> menu(String accountId) {
        accounts.accountSummary(accountId);
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT buttons_json,status,remote_json,published_at,updated_at FROM wechat_menu_draft WHERE account_id=?",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("buttons", parse(rs.getString("buttons_json"), new JSONObject()));
                    row.put("status", rs.getString("status"));
                    row.put("remote", parse(rs.getString("remote_json"), null));
                    row.put("publishedAt", rs.getTimestamp("published_at"));
                    row.put("updatedAt", rs.getTimestamp("updated_at"));
                    return row;
                }, accountId);
        return rows.isEmpty() ? Map.of("buttons", Map.of("button", List.of()), "status", "DRAFT") : rows.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveMenu(String accountId, Map<String, Object> body) {
        accounts.accountSummary(accountId);
        JSONObject buttons = menuButtons(body.get("buttons"));
        jdbc.update("INSERT INTO wechat_menu_draft(account_id,buttons_json,status) VALUES(?,?,'DRAFT') " +
                        "ON DUPLICATE KEY UPDATE buttons_json=VALUES(buttons_json),status='DRAFT',updated_at=CURRENT_TIMESTAMP",
                accountId, buttons.toJSONString());
        return menu(accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publishMenu(String accountId) throws Exception {
        Map<String, Object> draft = menu(accountId);
        Object buttons = draft.get("buttons");
        if (!(buttons instanceof Map<?, ?> map) || !(map.get("button") instanceof List<?> list) || list.isEmpty())
            throw new BusinessException(400, "请先配置公众号菜单");
        JSONObject response = accounts.apiPost(accountId, "/cgi-bin/menu/create", buttons);
        requireSuccess(response, "公众号菜单发布失败");
        jdbc.update("UPDATE wechat_menu_draft SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP WHERE account_id=?", accountId);
        return menu(accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importMenu(String accountId) throws Exception {
        accounts.accountSummary(accountId);
        JSONObject response = accounts.apiGet(accountId, "/cgi-bin/menu/get");
        requireSuccess(response, "读取公众号菜单失败");
        JSONObject buttons = new JSONObject();
        buttons.put("button", response.getJSONArray("button") == null ? new JSONArray() : response.getJSONArray("button"));
        jdbc.update("INSERT INTO wechat_menu_draft(account_id,buttons_json,status,remote_json,published_at) VALUES(?,?,'PUBLISHED',?,CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE buttons_json=VALUES(buttons_json),status='PUBLISHED',remote_json=VALUES(remote_json),published_at=CURRENT_TIMESTAMP",
                accountId, buttons.toJSONString(), response.toJSONString());
        return menu(accountId);
    }

    public List<Map<String, Object>> autoReplies(String accountId) {
        accounts.accountSummary(accountId);
        return jdbc.query("SELECT id,rule_type,keyword,match_mode,reply_type,content,media_id,priority,enabled,updated_at " +
                        "FROM wechat_autoreply_rule WHERE account_id=? ORDER BY priority DESC,id",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("ruleType", rs.getString("rule_type"));
                    row.put("keyword", rs.getString("keyword"));
                    row.put("matchMode", rs.getString("match_mode"));
                    row.put("replyType", rs.getString("reply_type"));
                    row.put("content", rs.getString("content"));
                    row.put("mediaId", rs.getString("media_id"));
                    row.put("priority", rs.getInt("priority"));
                    row.put("enabled", rs.getInt("enabled") == 1);
                    row.put("updatedAt", rs.getTimestamp("updated_at"));
                    return row;
                }, accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveAutoReply(String accountId, Long id, Map<String, Object> body) {
        accounts.accountSummary(accountId);
        String ruleType = upper(body.get("ruleType"), "KEYWORD");
        if (!Set.of("KEYWORD", "SUBSCRIBE", "DEFAULT").contains(ruleType)) throw new BusinessException(400, "自动回复规则类型不正确");
        String keyword = text(body.get("keyword"));
        if ("KEYWORD".equals(ruleType) && (keyword.isBlank() || keyword.length() > 80)) throw new BusinessException(400, "关键词回复必须填写 1 至 80 字的关键词");
        String matchMode = upper(body.get("matchMode"), "CONTAINS");
        if (!Set.of("CONTAINS", "EXACT").contains(matchMode)) throw new BusinessException(400, "关键词匹配方式不正确");
        String replyType = upper(body.get("replyType"), "TEXT");
        if (!Set.of("TEXT", "IMAGE", "VOICE", "VIDEO").contains(replyType)) throw new BusinessException(400, "回复类型不正确");
        String content = text(body.get("content"));
        String mediaId = text(body.get("mediaId"));
        if ("TEXT".equals(replyType) && (content.isBlank() || content.length() > 1000)) throw new BusinessException(400, "文字回复不能为空且最多 1000 字");
        if (!"TEXT".equals(replyType) && (mediaId.isBlank() || mediaId.length() > 128)) throw new BusinessException(400, "媒体回复需要填写已上传的素材 Media ID");
        int priority = body.get("priority") instanceof Number number ? Math.max(-1000, Math.min(1000, number.intValue())) : 0;
        boolean enabled = !(body.get("enabled") instanceof Boolean value) || value;
        if (id == null) {
            jdbc.update("INSERT INTO wechat_autoreply_rule(account_id,rule_type,keyword,match_mode,reply_type,content,media_id,priority,enabled) " +
                            "VALUES(?,?,?,?,?,?,?,?,?)", accountId, ruleType, keyword.isBlank() ? null : keyword, matchMode,
                    replyType, content.isBlank() ? null : content, mediaId.isBlank() ? null : mediaId, priority, enabled ? 1 : 0);
            id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            int updated = jdbc.update("UPDATE wechat_autoreply_rule SET rule_type=?,keyword=?,match_mode=?,reply_type=?,content=?,media_id=?,priority=?,enabled=? " +
                            "WHERE id=? AND account_id=?", ruleType, keyword.isBlank() ? null : keyword, matchMode, replyType,
                    content.isBlank() ? null : content, mediaId.isBlank() ? null : mediaId, priority, enabled ? 1 : 0, id, accountId);
            if (updated == 0) throw new BusinessException(404, "自动回复规则不存在");
        }
        Long savedId = id;
        if (savedId == null) throw new BusinessException(502, "自动回复保存后未取得记录编号");
        return autoReplies(accountId).stream().filter(row -> savedId.equals(row.get("id"))).findFirst().orElseThrow();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAutoReply(String accountId, long id) {
        if (jdbc.update("DELETE FROM wechat_autoreply_rule WHERE id=? AND account_id=?", id, accountId) == 0)
            throw new BusinessException(404, "自动回复规则不存在");
    }

    public List<Map<String, Object>> qrCodes(String accountId) {
        accounts.accountSummary(accountId);
        return jdbc.query("SELECT id,name,scene,qr_type,ticket,image_url,expire_seconds,scans,created_at FROM wechat_channel_qr " +
                        "WHERE account_id=? ORDER BY created_at DESC",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    row.put("scene", rs.getString("scene"));
                    row.put("type", rs.getString("qr_type"));
                    row.put("ticket", rs.getString("ticket"));
                    row.put("imageUrl", rs.getString("image_url"));
                    row.put("expireSeconds", rs.getObject("expire_seconds"));
                    row.put("scans", rs.getInt("scans"));
                    row.put("createdAt", rs.getTimestamp("created_at"));
                    return row;
                }, accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createQr(String accountId, Map<String, Object> body) throws Exception {
        accounts.accountSummary(accountId);
        String name = required(body.get("name"), "请填写渠道名称", 80);
        String scene = required(body.get("scene"), "请填写渠道场景值", 64);
        if (!scene.matches("[A-Za-z0-9_-]{1,64}")) throw new BusinessException(400, "渠道场景值只能包含字母、数字、下划线和短横线");
        String type = "PERMANENT".equalsIgnoreCase(text(body.get("type"))) ? "PERMANENT" : "TEMP";
        int expire = type.equals("TEMP") ? (body.get("expireSeconds") instanceof Number n ? Math.max(60, Math.min(2592000, n.intValue())) : 604800) : 0;
        JSONObject payload = new JSONObject();
        payload.put("action_name", type.equals("TEMP") ? "QR_SCENE" : "QR_LIMIT_STR_SCENE");
        JSONObject actionInfo = new JSONObject();
        JSONObject sceneNode = new JSONObject();
        sceneNode.put("scene_str", scene);
        actionInfo.put("scene", sceneNode);
        payload.put("action_info", actionInfo);
        if (type.equals("TEMP")) payload.put("expire_seconds", expire);
        JSONObject response = accounts.apiPost(accountId, "/cgi-bin/qrcode/create", payload);
        requireSuccess(response, "创建渠道二维码失败");
        String ticket = response.getString("ticket");
        if (ticket == null || ticket.isBlank()) throw new BusinessException(502, "微信未返回二维码票据");
        String id = UUID.randomUUID().toString();
        String imageUrl = "https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO wechat_channel_qr(id,account_id,name,scene,qr_type,ticket,image_url,expire_seconds) VALUES(?,?,?,?,?,?,?,?)",
                id, accountId, name, scene, type, ticket, imageUrl, type.equals("TEMP") ? expire : null);
        return qrCodes(accountId).stream().filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteQr(String accountId, String id) {
        if (jdbc.update("DELETE FROM wechat_channel_qr WHERE id=? AND account_id=?", id, accountId) == 0)
            throw new BusinessException(404, "渠道二维码不存在");
    }

    public List<Map<String, Object>> fans(String accountId) {
        accounts.accountSummary(accountId);
        return jdbc.query("SELECT openid,nickname,avatar,subscribed,tags_json,last_sync_at FROM wechat_fan WHERE account_id=? ORDER BY last_sync_at DESC LIMIT 500",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("openid", mask(rs.getString("openid")));
                    row.put("nickname", rs.getString("nickname"));
                    row.put("avatar", rs.getString("avatar"));
                    row.put("subscribed", rs.getInt("subscribed") == 1);
                    row.put("tags", parse(rs.getString("tags_json"), new JSONArray()));
                    row.put("lastSyncAt", rs.getTimestamp("last_sync_at"));
                    return row;
                }, accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncFans(String accountId) throws Exception {
        accounts.accountSummary(accountId);
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        String next = "";
        for (int page = 0; page < 10; page++) {
            String path = "/cgi-bin/user/get" + (next.isBlank() ? "" : "?next_openid=" + URLEncoder.encode(next, StandardCharsets.UTF_8));
            JSONObject result = accounts.apiGet(accountId, path);
            requireSuccess(result, "同步公众号粉丝失败");
            JSONObject data = result.getJSONObject("data");
            JSONArray openids = data == null ? null : data.getJSONArray("openid");
            if (openids == null || openids.isEmpty()) break;
            List<Map<String, Object>> batch = new ArrayList<>();
            for (Object openid : openids) batch.add(Map.of("openid", String.valueOf(openid), "lang", "zh_CN"));
            JSONObject users = accounts.apiPost(accountId, "/cgi-bin/user/info/batchget", Map.of("user_list", batch));
            requireSuccess(users, "读取公众号粉丝资料失败");
            JSONArray list = users.getJSONArray("user_info_list");
            if (list != null) for (Object raw : list) {
                JSONObject user = raw instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(raw));
                String openid = user.getString("openid");
                if (openid == null || !openid.matches("[A-Za-z0-9_-]{10,128}")) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nickname", truncate(user.getString("nickname"), 128));
                row.put("avatar", truncate(user.getString("headimgurl"), 512));
                row.put("subscribed", user.getIntValue("subscribe") == 1 ? 1 : 0);
                row.put("tags", user.getJSONArray("tagid_list") == null ? new JSONArray() : user.getJSONArray("tagid_list"));
                found.put(openid, row);
            }
            next = result.getString("next_openid");
            if (next == null || next.isBlank()) break;
        }
        for (var entry : found.entrySet()) {
            jdbc.update("INSERT INTO wechat_fan(account_id,openid,nickname,avatar,subscribed,tags_json,last_sync_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP) " +
                            "ON DUPLICATE KEY UPDATE nickname=VALUES(nickname),avatar=VALUES(avatar),subscribed=VALUES(subscribed),tags_json=VALUES(tags_json),last_sync_at=CURRENT_TIMESTAMP",
                    accountId, entry.getKey(), entry.getValue().get("nickname"), entry.getValue().get("avatar"), entry.getValue().get("subscribed"),
                    JSON.toJSONString(entry.getValue().get("tags")));
        }
        return Map.of("synced", found.size(), "total", found.size());
    }

    private static JSONObject menuButtons(Object raw) {
        JSONObject body = raw instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(raw));
        JSONArray buttons = body == null ? null : body.getJSONArray("button");
        if (buttons == null || buttons.isEmpty() || buttons.size() > 3) throw new BusinessException(400, "公众号菜单需要 1 至 3 个一级菜单");
        buttons.forEach(item -> {
            JSONObject button = item instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(item));
            String name = text(button.getString("name"));
            if (name.isBlank() || name.length() > 8) throw new BusinessException(400, "公众号菜单名称需要 1 至 8 个字符");
            JSONArray sub = button.getJSONArray("sub_button");
            if (sub != null && sub.size() > 5) throw new BusinessException(400, "单个一级菜单最多 5 个二级菜单");
        });
        return body;
    }

    private static void requireSuccess(JSONObject response, String message) {
        if (response.getIntValue("errcode") != 0) {
            int code = response.getIntValue("errcode");
            throw new BusinessException(502, message + "（错误码 " + code + "）");
        }
    }

    private static Object parse(String value, Object fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return JSON.parse(value); } catch (Exception error) { return fallback; }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String upper(Object value, String fallback) { String result = text(value).toUpperCase(Locale.ROOT); return result.isBlank() ? fallback : result; }
    private static String required(Object value, String message, int max) {
        String result = text(value);
        if (result.isBlank() || result.length() > max) throw new BusinessException(400, message);
        return result;
    }
    private static String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
    private static String mask(String openid) { return openid == null || openid.length() < 10 ? "***" : openid.substring(0, 4) + "***" + openid.substring(openid.length() - 4); }
}
