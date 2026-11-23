package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** A catalogue used to decide whether to add a template must be complete, not best-effort. */
@Service
@RequiredArgsConstructor
class WechatProvisioningCatalog {
    private final WechatAccountService accounts;
    private final JdbcTemplate jdbc;
    record Entry(String id, String title, String content, String primary, String deputy) {}
    record Existing(String id, boolean enabled, String fields) {}

    void checkAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) throw new BusinessException(400, "请选择公众号");
        Map<String, Object> account = accounts.accountSummary(accountId);
        if (!Boolean.TRUE.equals(account.get("enabled"))) throw new BusinessException(409, "该公众号已停用");
        if (!"OPEN_PLATFORM".equals(account.get("mode"))) throw new BusinessException(409, "自动配置仅适用于扫码授权公众号");
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Entry> refresh(String account) throws Exception {
        // Serialize snapshot replacement per account, not across tenants or for the entire setup job.
        if (jdbc.queryForList("SELECT id FROM wechat_mp_account WHERE id=? AND enabled=1 AND account_mode='OPEN_PLATFORM' FOR UPDATE",
                String.class, account).isEmpty()) throw new BusinessException(409, "公众号不可用");
        JSONObject reply = accounts.apiGet(account, "/cgi-bin/template/get_all_private_template");
        if (reply == null || (reply.containsKey("errcode") && !"0".equals(String.valueOf(reply.get("errcode"))))
                || !(reply.get("template_list") instanceof JSONArray list)) throw new BusinessException(502, "模板库同步失败");
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object raw : list) {
            if (!(raw instanceof JSONObject item) || !(item.get("template_id") instanceof String id)
                    || !id.matches("[A-Za-z0-9_-]{6,128}") || !ids.add(id)
                    || !(item.get("title") instanceof String title) || title.isBlank() || title.length() > 160
                    || !(item.get("content") instanceof String content)) throw new BusinessException(502, "模板库条目无效");
            entries.add(new Entry(id, title, content, item.getString("primary_industry"), item.getString("deputy_industry")));
        }
        jdbc.update("DELETE FROM wechat_mp_template_catalog WHERE account_id=?", account);
        for (Entry e : entries)
            jdbc.update("INSERT INTO wechat_mp_template_catalog(account_id,template_id,title,content,primary_industry,deputy_industry) VALUES(?,?,?,?,?,?)",
                    account, e.id(), e.title(), e.content(), e.primary(), e.deputy());
        jdbc.update("UPDATE wechat_mp_account SET last_checked_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=?", account);
        return entries;
    }

    Existing existing(String account, WechatProvisioningScheme scheme) {
        var matches = jdbc.query("SELECT template_id,enabled,fields_json FROM wechat_mp_template WHERE account_id=? AND role=? AND scenario=?",
                (rs, i) -> new Existing(rs.getString(1), rs.getBoolean(2), rs.getString(3)), account, scheme.role(), scheme.scenario());
        return matches.isEmpty() ? null : matches.get(0);
    }

    @Transactional
    public boolean insert(String account, WechatProvisioningScheme scheme, Entry verified, Map<String,String> fields) {
        // Recheck exact snapshot (Java string equality, not a case-insensitive SQL collation).
        var titles = scheme.titles();
        var current = jdbc.query("SELECT c.template_id,c.title,c.content FROM wechat_mp_account a JOIN wechat_mp_template_catalog c ON c.account_id=a.id " +
                        "WHERE a.id=? AND a.enabled=1 AND a.account_mode='OPEN_PLATFORM' FOR UPDATE",
                (rs, i) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), null, null), account)
                .stream().filter(e -> titles.contains(WechatProvisioningScheme.normalize(e.title()))).toList();
        if (current.size() != 1 || !current.get(0).id().equals(verified.id())
                || !current.get(0).title().equals(verified.title()) || !Objects.equals(current.get(0).content(), verified.content())) return false;
        // Never upsert: the unique key protects a manual save racing this insert.
        jdbc.update("INSERT INTO wechat_mp_template(account_id,role,scenario,template_id,fields_json,url,source,enabled) VALUES(?,?,?,?,?,?,'AUTHORITY',1)",
                account, scheme.role(), scheme.scenario(), verified.id(), JSON.toJSONString(fields), scheme.url().isEmpty() ? null : scheme.url());
        return true;
    }
}
