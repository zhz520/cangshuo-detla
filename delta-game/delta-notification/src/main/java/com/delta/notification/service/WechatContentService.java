package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class WechatContentService {
    private final JdbcTemplate jdbc;
    private final WechatAccountService accounts;
    private final SysConfigService configs;

    public List<Map<String, Object>> articles(String accountId) {
        accounts.accountSummary(accountId);
        return jdbc.query("SELECT id,title,author,digest,cover_url,remote_media_id,status,created_at,updated_at " +
                        "FROM wechat_article_draft WHERE account_id=? ORDER BY updated_at DESC",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id")); row.put("title", rs.getString("title"));
                    row.put("author", rs.getString("author")); row.put("digest", rs.getString("digest"));
                    row.put("coverUrl", rs.getString("cover_url")); row.put("remoteMediaId", rs.getString("remote_media_id"));
                    row.put("status", rs.getString("status")); row.put("createdAt", rs.getTimestamp("created_at"));
                    row.put("updatedAt", rs.getTimestamp("updated_at")); return row;
                }, accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveArticle(String accountId, Long id, Map<String, Object> body) {
        accounts.accountSummary(accountId);
        String title = required(body.get("title"), "请填写文章标题", 120);
        String author = optional(body.get("author"), 64);
        String digest = optional(body.get("digest"), 500);
        String html = optional(body.get("contentHtml"), 1_000_000);
        String coverUrl = optional(body.get("coverUrl"), 512);
        if (id == null) {
            jdbc.update("INSERT INTO wechat_article_draft(account_id,title,author,digest,content_html,cover_url,status) VALUES(?,?,?,?,?,?,'DRAFT')",
                    accountId, title, author, digest, html, coverUrl);
            id = jdbc.queryForObject("SELECT id FROM wechat_article_draft WHERE account_id=? AND title=? ORDER BY id DESC LIMIT 1", Long.class, accountId, title);
        } else if (jdbc.update("UPDATE wechat_article_draft SET title=?,author=?,digest=?,content_html=?,cover_url=?,status='DRAFT' WHERE id=? AND account_id=?",
                title, author, digest, html, coverUrl, id, accountId) == 0) throw new BusinessException(404, "图文草稿不存在");
        Long savedId = id;
        return articles(accountId).stream().filter(row -> savedId.equals(row.get("id"))).findFirst().orElseThrow();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteArticle(String accountId, long id) {
        if (jdbc.update("DELETE FROM wechat_article_draft WHERE id=? AND account_id=?", id, accountId) == 0)
            throw new BusinessException(404, "图文草稿不存在");
    }

    public List<Map<String, Object>> tasks(String accountId) {
        accounts.accountSummary(accountId);
        return jdbc.query("SELECT id,name,target_type,target_json,msg_type,content_json,status,created_by,approved_by,wechat_msg_id,error_text,created_at,updated_at " +
                        "FROM wechat_mass_send_task WHERE account_id=? ORDER BY updated_at DESC",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id")); row.put("name", rs.getString("name"));
                    row.put("targetType", rs.getString("target_type")); row.put("target", parse(rs.getString("target_json")));
                    row.put("msgType", rs.getString("msg_type")); row.put("content", parse(rs.getString("content_json")));
                    row.put("status", rs.getString("status")); row.put("createdBy", rs.getObject("created_by"));
                    row.put("approvedBy", rs.getObject("approved_by")); row.put("wechatMsgId", rs.getString("wechat_msg_id"));
                    row.put("errorText", rs.getString("error_text")); row.put("createdAt", rs.getTimestamp("created_at"));
                    row.put("updatedAt", rs.getTimestamp("updated_at")); return row;
                }, accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveTask(String accountId, Long id, Map<String, Object> body, long actor) {
        accounts.accountSummary(accountId);
        String name = required(body.get("name"), "请填写群发任务名称", 80);
        String targetType = upper(body.get("targetType"), "ALL");
        if (!Set.of("ALL", "TAG", "OPENIDS").contains(targetType)) throw new BusinessException(400, "群发范围不正确");
        String msgType = upper(body.get("msgType"), "TEXT");
        if (!Set.of("TEXT", "IMAGE", "MPNEWS").contains(msgType)) throw new BusinessException(400, "群发消息类型不正确");
        JSONObject target = body.get("target") instanceof Map<?, ?> map ? JSON.parseObject(JSON.toJSONString(map)) : new JSONObject();
        JSONObject content = body.get("content") instanceof Map<?, ?> map ? JSON.parseObject(JSON.toJSONString(map)) : new JSONObject();
        if (targetType.equals("TAG") && target.getLong("tagId") == null) throw new BusinessException(400, "按标签群发必须填写标签 ID");
        if (targetType.equals("OPENIDS") && (target.getJSONArray("openids") == null || target.getJSONArray("openids").isEmpty()))
            throw new BusinessException(400, "指定 OpenID 群发需要至少一个收件人");
        if (msgType.equals("TEXT") && optional(content.getString("text"), 2000).isBlank()) throw new BusinessException(400, "文字群发内容不能为空");
        if (!msgType.equals("TEXT") && optional(content.getString("mediaId"), 128).isBlank()) throw new BusinessException(400, "媒体群发需要 Media ID");
        if (id == null) {
            jdbc.update("INSERT INTO wechat_mass_send_task(account_id,name,target_type,target_json,msg_type,content_json,status,created_by) VALUES(?,?,?,?,?,?,'DRAFT',?)",
                    accountId, name, targetType, target.toJSONString(), msgType, content.toJSONString(), actor);
            id = jdbc.queryForObject("SELECT id FROM wechat_mass_send_task WHERE account_id=? AND name=? ORDER BY id DESC LIMIT 1", Long.class, accountId, name);
        } else {
            if (jdbc.update("UPDATE wechat_mass_send_task SET name=?,target_type=?,target_json=?,msg_type=?,content_json=?,error_text=NULL WHERE id=? AND account_id=? AND status IN ('DRAFT','REJECTED')",
                    name, targetType, target.toJSONString(), msgType, content.toJSONString(), id, accountId) == 0)
                throw new BusinessException(409, "当前群发任务不可编辑");
        }
        Long savedId = id;
        return tasks(accountId).stream().filter(row -> savedId.equals(row.get("id"))).findFirst().orElseThrow();
    }

    @Transactional(rollbackFor = Exception.class)
    public void transition(String accountId, long id, String action, long actor) {
        String status = jdbc.query("SELECT status FROM wechat_mass_send_task WHERE id=? AND account_id=?",
                rs -> rs.next() ? rs.getString(1) : null, id, accountId);
        if (status == null) throw new BusinessException(404, "群发任务不存在");
        switch (action) {
            case "submit" -> {
                if (!Set.of("DRAFT", "REJECTED").contains(status)) throw new BusinessException(409, "当前任务不能提交审批");
                jdbc.update("UPDATE wechat_mass_send_task SET status='PENDING_APPROVAL',error_text=NULL WHERE id=?", id);
            }
            case "approve" -> {
                if (!"PENDING_APPROVAL".equals(status)) throw new BusinessException(409, "当前任务不在待审批状态");
                jdbc.update("UPDATE wechat_mass_send_task SET status='APPROVED',approved_by=? WHERE id=?", actor, id);
            }
            case "reject" -> {
                if (!"PENDING_APPROVAL".equals(status)) throw new BusinessException(409, "当前任务不在待审批状态");
                jdbc.update("UPDATE wechat_mass_send_task SET status='REJECTED',approved_by=? WHERE id=?", actor, id);
            }
            case "cancel" -> {
                if (Set.of("SENT", "CANCELLED").contains(status)) throw new BusinessException(409, "当前任务不能取消");
                jdbc.update("UPDATE wechat_mass_send_task SET status='CANCELLED' WHERE id=?", id);
            }
            default -> throw new BusinessException(400, "群发动作不正确");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> send(String accountId, long id, long actor, boolean confirm) throws Exception {
        if (!confirm) throw new BusinessException(400, "真实群发必须再次确认");
        if (!"true".equalsIgnoreCase(configs.getConfigValue("wechat.mass_send_enabled", "false")))
            throw new BusinessException(403, "真实群发未启用，请管理员在系统配置中显式开启");
        Map<String, Object> task = tasks(accountId).stream().filter(row -> Long.valueOf(id).equals(row.get("id"))).findFirst()
                .orElseThrow(() -> new BusinessException(404, "群发任务不存在"));
        if (!"APPROVED".equals(task.get("status"))) throw new BusinessException(409, "群发任务尚未审批通过");
        if (Objects.equals(actor, ((Number) task.get("approvedBy")).longValue())) throw new BusinessException(403, "审批人不能同时执行群发");
        JSONObject content = (JSONObject) task.get("content");
        JSONObject payload = new JSONObject();
        String targetType = String.valueOf(task.get("targetType"));
        String msgType = String.valueOf(task.get("msgType"));
        if (targetType.equals("OPENIDS")) {
            payload.put("touser", ((JSONObject) task.get("target")).getJSONArray("openids"));
            payload.put("msgtype", msgType.equals("MPNEWS") ? "mpnews" : msgType.toLowerCase(Locale.ROOT));
        } else {
            JSONObject filter = new JSONObject();
            filter.put("is_to_all", targetType.equals("ALL"));
            if (targetType.equals("TAG")) filter.put("tag_id", ((JSONObject) task.get("target")).getLong("tagId"));
            payload.put("filter", filter);
            payload.put("msgtype", msgType.equals("MPNEWS") ? "mpnews" : msgType.toLowerCase(Locale.ROOT));
        }
        if (msgType.equals("TEXT")) payload.put("text", Map.of("content", content.getString("text")));
        else payload.put(msgType.equals("MPNEWS") ? "mpnews" : "image", Map.of("media_id", content.getString("mediaId")));
        String path = targetType.equals("OPENIDS") ? "/cgi-bin/message/mass/send" : "/cgi-bin/message/mass/sendall";
        JSONObject result = accounts.apiPost(accountId, path, payload);
        if (result.getIntValue("errcode") != 0) {
            jdbc.update("UPDATE wechat_mass_send_task SET status='FAILED',error_text=? WHERE id=?", "WECHAT_" + result.getIntValue("errcode"), id);
            throw new BusinessException(502, "群发失败（错误码 " + result.getIntValue("errcode") + "）");
        }
        jdbc.update("UPDATE wechat_mass_send_task SET status='SENT',wechat_msg_id=?,error_text=NULL WHERE id=?", result.getString("msg_id"), id);
        return tasks(accountId).stream().filter(row -> Long.valueOf(id).equals(row.get("id"))).findFirst().orElseThrow();
    }

    private static Object parse(String value) { try { return JSON.parse(value); } catch (Exception error) { return Map.of(); } }
    private static String required(Object value, String message, int max) { String result = optional(value, max); if (result.isBlank()) throw new BusinessException(400, message); return result; }
    private static String optional(Object value, int max) { String result = value == null ? "" : String.valueOf(value).trim(); if (result.length() > max) throw new BusinessException(400, "内容超过长度限制"); return result; }
    private static String upper(Object value, String fallback) { String result = value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT); return result.isBlank() ? fallback : result; }
}
