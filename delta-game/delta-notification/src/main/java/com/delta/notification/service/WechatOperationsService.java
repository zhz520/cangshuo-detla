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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Basic official-account operations. Only account-scoped data is stored here;
 * followers and messages are never treated as Delta customer identities.
 */
@Service
@RequiredArgsConstructor
public class WechatOperationsService {
    private static final int FAN_SYNC_LIMIT = 500;
    private static final int WECHAT_FAN_BATCH_SIZE = 100;
    private static final int FAN_LIST_PAGE_LIMIT = 10;
    private final JdbcTemplate jdbc;
    private final WechatAccountService accounts;
    private final WechatOpenPlatformClient openPlatform;

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
        JSONObject buttons = WechatMenuDefinition.normalize(body.get("buttons"), true);
        jdbc.update("INSERT INTO wechat_menu_draft(account_id,buttons_json,status) VALUES(?,?,'DRAFT') " +
                        "ON DUPLICATE KEY UPDATE buttons_json=VALUES(buttons_json),status='DRAFT',updated_at=CURRENT_TIMESTAMP",
                accountId, buttons.toJSONString());
        return menu(accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publishMenu(String accountId, Object expected) throws Exception {
        accounts.accountSummary(accountId);
        var rows = jdbc.queryForList("SELECT buttons_json FROM wechat_menu_draft WHERE account_id=? FOR UPDATE", String.class, accountId);
        if (rows.isEmpty()) throw new BusinessException(409, "请先保存公众号菜单草稿");
        JSONObject buttons = WechatMenuDefinition.normalize(JSON.parseObject(rows.get(0)), false);
        if (!buttons.equals(WechatMenuDefinition.normalize(expected, false)))
            throw new BusinessException(409, "草稿已被其他管理员修改，请刷新后重新预览");
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
        JSONObject buttons = WechatMenuDefinition.imported(response);
        jdbc.update("INSERT INTO wechat_menu_draft(account_id,buttons_json,status,remote_json,published_at) VALUES(?,?,'PUBLISHED',?,CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE buttons_json=VALUES(buttons_json),status='PUBLISHED',remote_json=VALUES(remote_json),published_at=CURRENT_TIMESTAMP",
                accountId, buttons.toJSONString(), response.toJSONString());
        return menu(accountId);
    }

    public List<Map<String, Object>> autoReplies(String accountId) {
        Map<String, Object> account = accounts.accountSummary(accountId);
        if (managedReplies(account)) return openPlatform.replyRules((String) account.get("appId")).rules();
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
        Map<String, Object> account = accounts.accountSummary(accountId);
        boolean managed = managedReplies(account);
        String ruleType = upper(body.get("ruleType"), "KEYWORD");
        if (!Set.of("KEYWORD", "SUBSCRIBE", "DEFAULT").contains(ruleType)) throw new BusinessException(400, "自动回复规则类型不正确");
        String keyword = text(body.get("keyword"));
        if ("KEYWORD".equals(ruleType) && (keyword.isBlank() || keyword.length() > 80)) throw new BusinessException(400, "关键词回复必须填写 1 至 80 字的关键词");
        String matchMode = upper(body.get("matchMode"), "CONTAINS");
        if (!Set.of("CONTAINS", "EXACT").contains(matchMode)) throw new BusinessException(400, "关键词匹配方式不正确");
        String replyType = upper(body.get("replyType"), "TEXT");
        if (!Set.of("TEXT", "IMAGE", "VOICE", "VIDEO").contains(replyType)) throw new BusinessException(400, "回复类型不正确");
        if (managed && !"TEXT".equals(replyType)) throw new BusinessException(400, "开放平台自动回复仅支持文字");
        String content = text(body.get("content"));
        String mediaId = text(body.get("mediaId"));
        if ("TEXT".equals(replyType) && (content.isBlank() || content.length() > 1000)) throw new BusinessException(400, "文字回复不能为空且最多 1000 字");
        if (!"TEXT".equals(replyType) && (mediaId.isBlank() || mediaId.length() > 128)) throw new BusinessException(400, "媒体回复需要填写已上传的素材 Media ID");
        int priority = body.get("priority") instanceof Number number ? Math.max(-1000, Math.min(1000, number.intValue())) : 0;
        boolean enabled = !(body.get("enabled") instanceof Boolean value) || value;
        if (managed) {
            String appId = (String) account.get("appId");
            var snapshot = openPlatform.replyRules(appId);
            List<Map<String, Object>> rules = new ArrayList<>(snapshot.rules());
            long ruleId;
            int position;
            if (id == null) {
                if (rules.size() >= 100) throw new BusinessException(400, "一个公众号最多配置 100 条自动回复");
                do { ruleId = ThreadLocalRandom.current().nextLong(1, 1L << 53); }
                while (managedRuleIndex(rules, ruleId) >= 0);
                position = rules.size();
            } else {
                ruleId = id;
                position = managedRuleIndex(rules, ruleId);
                if (position < 0) throw new BusinessException(404, "自动回复规则不存在");
                rules.remove(position);
            }
            var rule = Map.<String, Object>of("id", ruleId, "ruleType", ruleType,
                    "keyword", "KEYWORD".equals(ruleType) ? keyword : "", "matchMode", matchMode,
                    "replyType", replyType, "content", content, "priority", priority, "enabled", enabled);
            rules.add(position, rule);
            var saved = openPlatform.replaceReplyRules(appId, snapshot.revision(), rules).rules();
            int index = managedRuleIndex(saved, ruleId);
            if (index < 0) throw new BusinessException(502, "授权中心未返回已保存的自动回复");
            return saved.get(index);
        }
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
        Map<String, Object> account = accounts.accountSummary(accountId);
        if (managedReplies(account)) {
            String appId = (String) account.get("appId");
            var snapshot = openPlatform.replyRules(appId);
            List<Map<String, Object>> rules = new ArrayList<>(snapshot.rules());
            int index = managedRuleIndex(rules, id);
            if (index < 0) throw new BusinessException(404, "自动回复规则不存在");
            rules.remove(index);
            openPlatform.replaceReplyRules(appId, snapshot.revision(), rules);
            return;
        }
        if (jdbc.update("DELETE FROM wechat_autoreply_rule WHERE id=? AND account_id=?", id, accountId) == 0)
            throw new BusinessException(404, "自动回复规则不存在");
    }

    private static boolean managedReplies(Map<String, Object> account) {
        return account != null && "OPEN_PLATFORM".equals(account.get("mode"));
    }

    private static int managedRuleIndex(List<Map<String, Object>> rules, long id) {
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index).get("id") instanceof Number candidate && candidate.longValue() == id) return index;
        }
        return -1;
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
        List<String> openids = collectFollowerIds(accountId);
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        for (int offset = 0; offset < openids.size(); offset += WECHAT_FAN_BATCH_SIZE) {
            List<String> chunk = openids.subList(offset, Math.min(offset + WECHAT_FAN_BATCH_SIZE, openids.size()));
            Set<String> requested = new HashSet<>(chunk);
            List<Map<String, String>> batch = chunk.stream()
                    .map(openid -> Map.of("openid", openid, "lang", "zh_CN")).toList();
            JSONObject users = accounts.apiPost(accountId, "/cgi-bin/user/info/batchget", Map.of("user_list", batch));
            requireSuccess(users, "读取公众号粉丝资料失败");
            JSONArray list = users.getJSONArray("user_info_list");
            if (list != null) for (Object raw : list) {
                JSONObject user = raw instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(raw));
                String openid = user.getString("openid");
                if (!requested.contains(openid)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nickname", truncate(user.getString("nickname"), 128));
                row.put("avatar", truncate(user.getString("headimgurl"), 512));
                row.put("subscribed", user.getIntValue("subscribe") == 1 ? 1 : 0);
                row.put("tags", user.getJSONArray("tagid_list") == null ? new JSONArray() : user.getJSONArray("tagid_list"));
                found.put(openid, row);
            }
        }
        for (var entry : found.entrySet()) {
            jdbc.update("INSERT INTO wechat_fan(account_id,openid,nickname,avatar,subscribed,tags_json,last_sync_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP) " +
                            "ON DUPLICATE KEY UPDATE nickname=VALUES(nickname),avatar=VALUES(avatar),subscribed=VALUES(subscribed),tags_json=VALUES(tags_json),last_sync_at=CURRENT_TIMESTAMP",
                    accountId, entry.getKey(), entry.getValue().get("nickname"), entry.getValue().get("avatar"), entry.getValue().get("subscribed"),
                    JSON.toJSONString(entry.getValue().get("tags")));
        }
        return Map.of("synced", found.size(), "total", found.size());
    }

    private List<String> collectFollowerIds(String accountId) throws Exception {
        Set<String> distinct = new LinkedHashSet<>();
        Set<String> seenCursors = new HashSet<>();
        String next = "";
        for (int page = 0; page < FAN_LIST_PAGE_LIMIT && distinct.size() < FAN_SYNC_LIMIT; page++) {
            String path = "/cgi-bin/user/get" + (next.isBlank() ? "" : "?next_openid=" + URLEncoder.encode(next, StandardCharsets.UTF_8));
            JSONObject result = accounts.apiGet(accountId, path);
            requireSuccess(result, "同步公众号粉丝失败");
            JSONObject data = result.getJSONObject("data");
            JSONArray openids = data == null ? null : data.getJSONArray("openid");
            if (openids == null || openids.isEmpty()) break;
            for (Object raw : openids) {
                if (raw instanceof String openid && openid.matches("[A-Za-z0-9_-]{10,128}")) distinct.add(openid);
                if (distinct.size() == FAN_SYNC_LIMIT) break;
            }
            next = result.getString("next_openid");
            if (next == null || next.isBlank() || !seenCursors.add(next)) break;
        }
        return new ArrayList<>(distinct);
    }

    public List<Map<String, Object>> tags(String accountId) {
        accounts.accountSummary(accountId);
        return jdbc.query("SELECT tag_id,name,fan_count,synced_at FROM wechat_tag WHERE account_id=? ORDER BY name",
                (rs, n) -> Map.of("tagId", rs.getLong("tag_id"), "name", rs.getString("name"),
                        "fanCount", rs.getInt("fan_count"), "syncedAt", rs.getTimestamp("synced_at")), accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> syncTags(String accountId) throws Exception {
        accounts.accountSummary(accountId);
        JSONObject response = accounts.apiGet(accountId, "/cgi-bin/tags/get");
        requireSuccess(response, "同步公众号标签失败");
        JSONArray items = response.getJSONArray("tags");
        jdbc.update("DELETE FROM wechat_tag WHERE account_id=?", accountId);
        if (items != null) for (Object raw : items) {
            JSONObject item = raw instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(raw));
            String name = text(item.getString("name"));
            if (name.isBlank()) continue;
            jdbc.update("INSERT INTO wechat_tag(account_id,tag_id,name,fan_count) VALUES(?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE name=VALUES(name),fan_count=VALUES(fan_count),synced_at=CURRENT_TIMESTAMP",
                    accountId, item.getLongValue("id"), truncate(name, 80), item.getIntValue("count"));
        }
        return tags(accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveTag(String accountId, Long tagId, String name) throws Exception {
        accounts.accountSummary(accountId);
        name = text(name);
        if (name.isBlank() || name.length() > 30) throw new BusinessException(400, "标签名称需要 1 至 30 个字符");
        JSONObject tag = new JSONObject(); tag.put("name", name);
        if (tagId != null) tag.put("id", tagId);
        JSONObject response = accounts.apiPost(accountId, tagId == null ? "/cgi-bin/tags/create" : "/cgi-bin/tags/update", Map.of("tag", tag));
        requireSuccess(response, tagId == null ? "创建公众号标签失败" : "更新公众号标签失败");
        if (tagId == null) tagId = response.getJSONObject("tag").getLong("id");
        jdbc.update("INSERT INTO wechat_tag(account_id,tag_id,name,fan_count) VALUES(?,?,?,0) " +
                        "ON DUPLICATE KEY UPDATE name=VALUES(name),synced_at=CURRENT_TIMESTAMP",
                accountId, tagId, name);
        Long savedTagId = tagId;
        return tags(accountId).stream().filter(row -> Objects.equals(savedTagId, row.get("tagId"))).findFirst().orElseThrow();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTag(String accountId, long tagId) throws Exception {
        JSONObject response = accounts.apiPost(accountId, "/cgi-bin/tags/delete", Map.of("tag", Map.of("id", tagId)));
        requireSuccess(response, "删除公众号标签失败");
        jdbc.update("DELETE FROM wechat_tag WHERE account_id=? AND tag_id=?", accountId, tagId);
    }

    public void batchTagFans(String accountId, long tagId, List<String> openids) throws Exception {
        if (openids == null || openids.isEmpty() || openids.size() > 1000) throw new BusinessException(400, "请选择 1 至 1000 个粉丝");
        for (String openid : openids) if (openid == null || !openid.matches("[A-Za-z0-9_-]{10,128}")) throw new BusinessException(400, "粉丝 OpenID 格式不正确");
        JSONObject response = accounts.apiPost(accountId, "/cgi-bin/tags/members/batchtagging",
                Map.of("openid_list", openids, "tagid", tagId));
        requireSuccess(response, "批量打标签失败");
    }

    public List<Map<String, Object>> materials(String accountId, String type) {
        accounts.accountSummary(accountId);
        String normalized = Set.of("image", "voice", "video", "news").contains(text(type).toLowerCase(Locale.ROOT)) ? text(type).toLowerCase(Locale.ROOT) : null;
        return jdbc.query("SELECT media_id,material_type,name,url,update_time FROM wechat_material WHERE account_id=?" +
                        (normalized == null ? "" : " AND material_type=?") + " ORDER BY update_time DESC LIMIT 500",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mediaId", rs.getString("media_id")); row.put("type", rs.getString("material_type"));
                    row.put("name", rs.getString("name")); row.put("url", rs.getString("url")); row.put("updateTime", rs.getTimestamp("update_time"));
                    return row;
                }, normalized == null ? new Object[]{accountId} : new Object[]{accountId, normalized});
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncMaterials(String accountId, String type) throws Exception {
        String normalized = Set.of("image", "voice", "video", "news").contains(text(type).toLowerCase(Locale.ROOT)) ? text(type).toLowerCase(Locale.ROOT) : "image";
        int offset = 0, synced = 0;
        for (int page = 0; page < 10; page++) {
            JSONObject response = accounts.apiPost(accountId, "/cgi-bin/material/batchget_material",
                    Map.of("type", normalized, "offset", offset, "count", 20));
            requireSuccess(response, "同步公众号素材失败");
            JSONArray items = response.getJSONArray("item");
            if (items == null || items.isEmpty()) break;
            for (Object raw : items) {
                JSONObject item = raw instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(raw));
                String mediaId = item.getString("media_id");
                if (mediaId == null || mediaId.isBlank()) continue;
                String url = item.getString("url");
                if (url == null && item.getJSONArray("news_item") != null && !item.getJSONArray("news_item").isEmpty())
                    url = item.getJSONArray("news_item").getJSONObject(0).getString("thumb_url");
                long epoch = item.getLongValue("update_time");
                LocalDateTime updated = epoch > 0 ? LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.systemDefault()) : null;
                jdbc.update("INSERT INTO wechat_material(account_id,media_id,material_type,name,url,update_time) VALUES(?,?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE name=VALUES(name),url=VALUES(url),update_time=VALUES(update_time)",
                        accountId, mediaId, normalized, truncate(item.getString("name"), 200), truncate(url, 1024), updated);
                synced++;
            }
            offset += items.size();
            if (offset >= response.getIntValue("total_count")) break;
        }
        return Map.of("synced", synced, "type", normalized);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> uploadMaterial(String accountId, String type, String filename, String contentType, byte[] bytes) throws Exception {
        String normalized = text(type).toLowerCase(Locale.ROOT);
        JSONObject response = accounts.uploadMaterial(accountId, normalized, filename, contentType, bytes);
        requireSuccess(response, "上传公众号素材失败");
        String mediaId = response.getString("media_id");
        if (mediaId == null || mediaId.isBlank()) throw new BusinessException(502, "微信未返回素材 Media ID");
        String url = response.getString("url");
        jdbc.update("INSERT INTO wechat_material(account_id,media_id,material_type,name,url,update_time) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE name=VALUES(name),url=VALUES(url),update_time=CURRENT_TIMESTAMP",
                accountId, mediaId, normalized, truncate(filename, 200), truncate(url, 1024));
        return materials(accountId, normalized).stream().filter(row -> mediaId.equals(row.get("mediaId"))).findFirst().orElseThrow();
    }

    public Map<String, Object> stats(String accountId) {
        accounts.accountSummary(accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fans", jdbc.queryForObject("SELECT COUNT(*) FROM wechat_fan WHERE account_id=?", Long.class, accountId));
        result.put("subscribed", jdbc.queryForObject("SELECT COUNT(*) FROM wechat_fan WHERE account_id=? AND subscribed=1", Long.class, accountId));
        result.put("messages", jdbc.queryForObject("SELECT COUNT(*) FROM wechat_message WHERE account_id=?", Long.class, accountId));
        result.put("qrScans", jdbc.queryForObject("SELECT COALESCE(SUM(scans),0) FROM wechat_channel_qr WHERE account_id=?", Long.class, accountId));
        result.put("materials", jdbc.queryForObject("SELECT COUNT(*) FROM wechat_material WHERE account_id=?", Long.class, accountId));
        result.put("lastMessageAt", jdbc.queryForObject("SELECT MAX(created_at) FROM wechat_message WHERE account_id=?", Timestamp.class, accountId));
        return result;
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
