package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.SecretCipher;
import com.delta.platform.PlatformService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Owns the tenant's official account credentials and the templates that are
 * valid for that account. Template IDs are account-specific and must never be
 * copied between tenants or between official accounts.
 */
@Service
@RequiredArgsConstructor
public class WechatAccountService {
    public static final List<String> ROLES = List.of("USER", "PLAYER", "ADMIN", "CS");
    public static final List<String> SCENARIOS = List.of(
            "new_order", "order_status", "refund_status", "complaint_status", "staff_review");
    private static final Pattern TEMPLATE_FIELD = Pattern.compile("\\{\\{([A-Za-z_]+[0-9]*)\\.DATA}}");

    private final JdbcTemplate jdbc;
    private final SecretCipher secrets;
    private final PlatformService platform;
    private final WechatOpenPlatformClient openPlatform;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ConcurrentHashMap<String, AccessToken> accessTokens = new ConcurrentHashMap<>();

    private record Account(String id, String name, String appId, String encryptedSecret,
                           String mode, boolean enabled, boolean defaultAccount) {}
    private record AccessToken(String value, long expiresAt) {}

    public List<Map<String, Object>> accounts() {
        return jdbc.query("SELECT id,name,app_id,account_mode,is_default,enabled,last_checked_at,last_error," +
                                "CASE WHEN app_secret_encrypted IS NULL OR app_secret_encrypted='' THEN 0 ELSE 1 END AS secret_configured " +
                                "FROM wechat_mp_account ORDER BY is_default DESC, created_at ASC",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    row.put("appId", rs.getString("app_id"));
                    row.put("mode", rs.getString("account_mode"));
                    row.put("defaultAccount", rs.getInt("is_default") == 1);
                    row.put("enabled", rs.getInt("enabled") == 1);
                    row.put("secretConfigured", rs.getInt("secret_configured") == 1);
                    row.put("lastCheckedAt", rs.getTimestamp("last_checked_at"));
                    row.put("lastError", rs.getString("last_error"));
                    return row;
                });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveAccount(Map<String, Object> body) {
        String id = text(body.get("id"));
        String name = required(body.get("name"), "请填写公众号名称", 80);
        String appId = required(body.get("appId"), "请填写公众号 AppID", 64);
        if (!appId.matches("[A-Za-z0-9_-]{6,64}")) throw new BusinessException(400, "公众号 AppID 格式不正确");
        String mode = "OPEN_PLATFORM".equalsIgnoreCase(text(body.get("mode"))) ? "OPEN_PLATFORM" : "DIRECT";
        boolean makeDefault = bool(body.get("defaultAccount"));
        boolean enabled = body.get("enabled") == null || bool(body.get("enabled"));

        Account existing = id.isBlank() ? null : requireAccount(id);
        if (!id.isBlank() && existing == null) throw new BusinessException(404, "公众号账号不存在");
        if ("OPEN_PLATFORM".equals(mode) || (existing != null && "OPEN_PLATFORM".equals(existing.mode())))
            throw new BusinessException(400, "开放平台公众号只能通过微信扫码授权，请勿手动填写");
        String newSecret = text(body.get("appSecret"));
        if (newSecret.length() > 256) throw new BusinessException(400, "公众号 AppSecret 过长");
        if (newSecret.isBlank() && (existing == null || existing.encryptedSecret().isBlank()) && "DIRECT".equals(mode))
            throw new BusinessException(400, "直连模式必须填写公众号 AppSecret");

        String encryptedSecret = newSecret.isBlank() ? (existing == null ? "" : existing.encryptedSecret()) : secrets.encrypt(newSecret);
        String callbackToken = text(body.get("callbackToken"));
        String aesKey = text(body.get("aesKey"));
        String encryptedCallbackToken = callbackToken.isBlank()
                ? (existing == null ? "" : jdbc.queryForObject("SELECT callback_token_encrypted FROM wechat_mp_account WHERE id=?", String.class, existing.id()))
                : secrets.encrypt(callbackToken);
        String encryptedAesKey = aesKey.isBlank()
                ? (existing == null ? "" : jdbc.queryForObject("SELECT aes_key_encrypted FROM wechat_mp_account WHERE id=?", String.class, existing.id()))
                : secrets.encrypt(aesKey);

        try {
            if (existing == null) {
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wechat_mp_account", Integer.class);
                boolean first = count == null || count == 0;
                String generatedId = UUID.randomUUID().toString();
                if (makeDefault || first) jdbc.update("UPDATE wechat_mp_account SET is_default=0");
                jdbc.update("INSERT INTO wechat_mp_account(id,name,app_id,app_secret_encrypted,callback_token_encrypted," +
                                "aes_key_encrypted,account_mode,is_default,enabled) VALUES(?,?,?,?,?,?,?,?,?)",
                        generatedId, name, appId, encryptedSecret, encryptedCallbackToken, encryptedAesKey, mode,
                        makeDefault || first ? 1 : 0, enabled ? 1 : 0);
                return accountSummary(generatedId);
            }
            if (makeDefault) jdbc.update("UPDATE wechat_mp_account SET is_default=0");
            jdbc.update("UPDATE wechat_mp_account SET name=?,app_id=?,app_secret_encrypted=?,callback_token_encrypted=?," +
                            "aes_key_encrypted=?,account_mode=?,is_default=?,enabled=?,last_error=NULL WHERE id=?",
                    name, appId, encryptedSecret, encryptedCallbackToken, encryptedAesKey, mode,
                    makeDefault ? 1 : existing.defaultAccount() ? 1 : 0, enabled ? 1 : 0, existing.id());
            return accountSummary(existing.id());
        } catch (DuplicateKeyException error) {
            throw new BusinessException(409, "该公众号 AppID 已登记");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importAuthorizedAccount(String appId, String name) {
        if (appId == null || !appId.matches("wx[A-Za-z0-9]{16}"))
            throw new BusinessException(502, "授权中心公众号 AppID 无效");
        String id = UUID.randomUUID().toString();
        String display = truncate(name,80);
        jdbc.update("INSERT INTO wechat_mp_account(id,name,app_id,account_mode,is_default,enabled) " +
                "VALUES(?,?,?,'OPEN_PLATFORM',0,1) ON DUPLICATE KEY UPDATE " +
                "name=CASE WHEN account_mode='OPEN_PLATFORM' THEN VALUES(name) ELSE name END," +
                "enabled=CASE WHEN account_mode='OPEN_PLATFORM' THEN 1 ELSE enabled END," +
                "last_error=CASE WHEN account_mode='OPEN_PLATFORM' THEN NULL ELSE last_error END", id,
                display == null || display.isBlank() ? appId : display, appId);
        // If this AppID already uses direct credentials, preserve settings and
        // subscriber identity. An existing binding must never silently switch.
        return accounts().stream().filter(row -> appId.equals(row.get("appId"))).findFirst()
                .orElseThrow(() -> new BusinessException(502, "公众号授权未保存，请重试"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAccount(String id) {
        Account account = requireAccount(id);
        if (account == null) throw new BusinessException(404, "公众号账号不存在");
        jdbc.update("DELETE FROM wechat_mp_template WHERE account_id=?", id);
        jdbc.update("DELETE FROM wechat_mp_template_catalog WHERE account_id=?", id);
        jdbc.update("DELETE FROM wechat_mp_account WHERE id=?", id);
        if (account.defaultAccount()) {
            List<String> candidates = jdbc.queryForList("SELECT id FROM wechat_mp_account WHERE account_mode='DIRECT' AND enabled=1 ORDER BY created_at ASC LIMIT 1", String.class);
            if (!candidates.isEmpty()) jdbc.update("UPDATE wechat_mp_account SET is_default=1 WHERE id=?", candidates.get(0));
        }
    }

    public Map<String, Object> accountSummary(String id) {
        return accounts().stream().filter(row -> id.equals(row.get("id"))).findFirst()
                .orElseThrow(() -> new BusinessException(404, "公众号账号不存在"));
    }

    public List<Map<String, Object>> catalog(String accountId) {
        requireAccount(accountId);
        return jdbc.query("SELECT template_id,title,content,primary_industry,deputy_industry,synced_at " +
                                "FROM wechat_mp_template_catalog WHERE account_id=? ORDER BY title",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("templateId", rs.getString("template_id"));
                    row.put("title", rs.getString("title"));
                    row.put("content", rs.getString("content"));
                    row.put("primaryIndustry", rs.getString("primary_industry"));
                    row.put("deputyIndustry", rs.getString("deputy_industry"));
                    row.put("syncedAt", rs.getTimestamp("synced_at"));
                    return row;
                }, accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> syncTemplates(String accountId) throws Exception {
        Account account = requireAccount(accountId);
        if (account == null) throw new BusinessException(404, "公众号账号不存在");
        String token = accessToken(account);
        JSONObject response = get("https://api.weixin.qq.com/cgi-bin/template/get_all_private_template?access_token=" + encode(token));
        if (response.getIntValue("errcode") != 0) {
            updateError(accountId, "WECHAT_" + response.getIntValue("errcode"));
            throw new BusinessException(502, "公众号模板同步失败，请检查接口权限（错误码 " + response.getIntValue("errcode") + "）");
        }
        JSONArray items = response.getJSONArray("template_list");
        jdbc.update("DELETE FROM wechat_mp_template_catalog WHERE account_id=?", accountId);
        if (items != null) for (Object raw : items) {
            JSONObject item = raw instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(raw));
            String templateId = item.getString("template_id");
            if (templateId == null || templateId.isBlank() || templateId.length() > 128) continue;
            jdbc.update("INSERT INTO wechat_mp_template_catalog(account_id,template_id,title,content,primary_industry,deputy_industry) " +
                            "VALUES(?,?,?,?,?,?)", accountId, templateId,
                    truncate(item.getString("title"), 160), item.getString("content"),
                    truncate(item.getString("primary_industry"), 80), truncate(item.getString("deputy_industry"), 80));
        }
        jdbc.update("UPDATE wechat_mp_account SET last_checked_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=?", accountId);
        return catalog(accountId);
    }

    public Map<String, Object> readiness(String accountId) {
        Account account = requireAccount(accountId);
        if (account == null) throw new BusinessException(404, "公众号账号不存在");
        boolean managed = "OPEN_PLATFORM".equals(account.mode());
        Set<String> catalogIds = managed ? new HashSet<>(jdbc.queryForList(
                "SELECT template_id FROM wechat_mp_template_catalog WHERE account_id=?", String.class, accountId)) : Set.of();
        Map<String, JSONObject> configured = templates(accountId);
        List<Map<String, Object>> templates = new ArrayList<>();
        for (String role : ROLES) for (String scenario : SCENARIOS) {
            JSONObject template = configured.get(role + ":" + scenario);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", role);
            row.put("scenario", scenario);
            row.put("configured", template != null);
            boolean enabled = template != null && template.getBooleanValue("enabled");
            row.put("enabled", enabled);
            row.put("templateId", template == null ? "" : template.getString("templateId"));
            row.put("fields", template == null ? new JSONObject() : template.getJSONObject("fields"));
            row.put("url", template == null ? "" : Objects.toString(template.getString("url"), ""));
            row.put("miniprogram", template == null ? new JSONObject() : template.getJSONObject("miniprogram"));
            row.put("status", template == null ? "TEMPLATE_MISSING" : !enabled ? "DISABLED"
                    : managed && !catalogIds.contains(template.getString("templateId")) ? "CATALOG_MISSING" : "READY");
            templates.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("appId", account.appId());
        result.put("mode", account.mode());
        result.put("enabled", account.enabled());
        result.put("secretConfigured", !account.encryptedSecret().isBlank());
        result.put("callbackConfigured", callbackConfigured(accountId));
        result.put("templates", templates);
        result.put("authoritySchemes", authoritySchemes());
        // Local configuration readiness is not a successful send or a live provider check.
        result.put("ready", account.enabled() && (managed || !account.encryptedSecret().isBlank())
                && templates.stream().allMatch(row -> "READY".equals(row.get("status"))));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveTemplate(String accountId, Map<String, Object> body) {
        Account account = requireAccount(accountId);
        if (account == null) throw new BusinessException(404, "公众号账号不存在");
        String role = text(body.get("role")).toUpperCase(Locale.ROOT);
        String scenario = text(body.get("scenario")).toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) throw new BusinessException(400, "通知角色不正确");
        if (!SCENARIOS.contains(scenario)) throw new BusinessException(400, "通知场景不正确");
        String templateId = required(body.get("templateId"), "请选择或填写微信模板 ID", 128);
        if (!templateId.matches("[A-Za-z0-9_-]{6,128}")) throw new BusinessException(400, "微信模板 ID 格式不正确");
        boolean enabled = body.get("enabled") == null || bool(body.get("enabled"));
        JSONObject fields = body.get("fields") instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(body.get("fields")));
        if (fields == null || fields.isEmpty()) {
            Map<String, Object> authority = authorityScheme(role, scenario);
            if (authority != null && authority.get("fields") instanceof Map<?, ?> map)
                fields = JSON.parseObject(JSON.toJSONString(map));
        }
        fields = validateFields(fields);
        if ("OPEN_PLATFORM".equals(account.mode())) {
            List<String> matched = jdbc.query("SELECT content FROM wechat_mp_template_catalog WHERE account_id=? AND template_id=?",
                    (rs, index) -> Objects.toString(rs.getString("content"), ""), accountId, templateId);
            if (matched.isEmpty()) {
                Integer stored = enabled ? 0 : jdbc.queryForObject("SELECT COUNT(*) FROM wechat_mp_template " +
                        "WHERE account_id=? AND role=? AND scenario=? AND template_id=?", Integer.class,
                        accountId, role, scenario, templateId);
                if (stored == null || stored == 0)
                    throw new BusinessException(409, "请先同步并选择本公众号拥有的微信模板");
            }
            Set<String> expected = new HashSet<>();
            if (!matched.isEmpty())
                TEMPLATE_FIELD.matcher(matched.get(0)).results().forEach(match -> expected.add(match.group(1)));
            if (!expected.isEmpty() && !expected.equals(fields.keySet()))
                throw new BusinessException(400, "模板参数必须与该微信模板的变量一致");
        }
        String url = text(body.get("url"));
        if (!url.isBlank()) validateUrl(url);
        JSONObject mini = null;
        if (body.get("miniprogram") instanceof Map<?, ?> map && !map.isEmpty()) {
            mini = JSON.parseObject(JSON.toJSONString(map));
            applyMiniProgramValidation(mini);
        }
        String source = "AUTHORITY".equalsIgnoreCase(text(body.get("source"))) ? "AUTHORITY" : "LOCAL";
        jdbc.update("INSERT INTO wechat_mp_template(account_id,role,scenario,template_id,fields_json,url,miniprogram_json,source,enabled) " +
                        "VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE template_id=VALUES(template_id),fields_json=VALUES(fields_json)," +
                        "url=VALUES(url),miniprogram_json=VALUES(miniprogram_json),source=VALUES(source),enabled=VALUES(enabled)",
                accountId, role, scenario, templateId, fields.toJSONString(),
                url.isBlank() ? null : url, mini == null ? null : mini.toJSONString(), source, enabled ? 1 : 0);
        return readiness(accountId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(String accountId, String role, String scenario) {
        jdbc.update("DELETE FROM wechat_mp_template WHERE account_id=? AND role=? AND scenario=?",
                accountId, role.toUpperCase(Locale.ROOT), scenario.toLowerCase(Locale.ROOT));
    }

    public JSONObject templateFor(String userType, String scenario) {
        Account account = defaultAccount();
        return account == null ? null : templateFor(account.id(), userType, scenario);
    }

    public JSONObject templateFor(String accountId, String userType, String scenario) {
        String role = canonicalRole(userType);
        List<JSONObject> templates = jdbc.query("SELECT t.template_id,t.fields_json,t.url,t.miniprogram_json " +
                        notificationTemplates() + " AND t.scenario=?",
                (rs, n) -> templateRow(rs.getString("template_id"), rs.getString("fields_json"),
                        rs.getString("url"), rs.getString("miniprogram_json")),
                accountId, role, scenario);
        return templates.isEmpty() ? null : templates.get(0);
    }

    /** Raw verified schemes for provisioning; presentation truncation/defaults must not drive writes. */
    JsonNode provisioningSchemes() {
        JsonNode project = platform.projectSnapshot();
        return project == null ? null : project.path("wechatTemplates").path("schemes");
    }

    public List<Map<String, Object>> authoritySchemes() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            JsonNode schemes = provisioningSchemes();
            if (schemes == null || !schemes.isArray()) return result;
            for (JsonNode scheme : schemes) {
                if (result.size() >= 40) break;
                String role = scheme.path("role").asText("").toUpperCase(Locale.ROOT);
                String scenario = scheme.path("scenario").asText("").toLowerCase(Locale.ROOT);
                if (!ROLES.contains(role) || !SCENARIOS.contains(scenario)) continue;
                Map<String, Object> fields = new LinkedHashMap<>();
                JsonNode fieldNode = scheme.path("fields");
                if (fieldNode.isObject()) {
                    var iterator = fieldNode.fields();
                    while (iterator.hasNext() && fields.size() < 20) {
                        var entry = iterator.next();
                        String source = entry.getValue().asText("");
                        if (entry.getKey().matches("[A-Za-z_]+[0-9]*") && source.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                            fields.put(entry.getKey(), source);
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("role", role);
                row.put("scenario", scenario);
                row.put("name", truncate(scheme.path("name").asText(""), 80));
                row.put("templateHint", truncate(scheme.path("templateHint").asText(""), 160));
                row.put("example", truncate(scheme.path("example").asText(""), 240));
                JsonNode suggestions = scheme.path("fieldSuggestions");
                if (suggestions.isArray() && suggestions.size() <= 20) row.put("fieldSuggestions", suggestions);
                row.put("fields", fields);
                row.put("url", truncate(scheme.path("url").asText(""), 2000));
                row.put("version", scheme.path("version").asInt(1));
                result.add(row);
            }
        } catch (Exception ignored) {
            // A missing or stale authority snapshot must not hide the local account readiness.
        }
        return result;
    }

    public Map<String, Object> authorityScheme(String role, String scenario) {
        return authoritySchemes().stream()
                .filter(row -> role.equals(row.get("role")) && scenario.equals(row.get("scenario")))
                .findFirst().orElse(null);
    }

    public List<String> missingScenarios(String userType) {
        Account account = defaultAccount();
        return account == null ? SCENARIOS : missingScenarios(account.id(),userType);
    }

    public List<String> missingScenarios(String accountId, String userType) {
        String role = canonicalRole(userType);
        Set<String> configured = new HashSet<>(jdbc.queryForList(
                "SELECT t.scenario " + notificationTemplates(), String.class, accountId, role));
        return SCENARIOS.stream().filter(scenario -> !configured.contains(scenario)).toList();
    }

    // Match the operator readiness view: a stale managed mapping may no longer belong to this catalog.
    private static String notificationTemplates() {
        return "FROM wechat_mp_template t JOIN wechat_mp_account a ON a.id=t.account_id " +
                "WHERE t.account_id=? AND t.role=? AND t.enabled=1 AND a.enabled=1 AND " +
                "(a.account_mode='DIRECT' OR EXISTS (SELECT 1 FROM wechat_mp_template_catalog c " +
                "WHERE c.account_id=t.account_id AND c.template_id=t.template_id))";
    }

    public String defaultAppId() {
        Account account = defaultAccount();
        return account == null ? "" : account.appId();
    }

    public boolean hasDefaultAccount() {
        return defaultAccount() != null;
    }

    public String defaultAppSecret() {
        Account account = defaultAccount();
        return account == null || account.encryptedSecret().isBlank() ? "" : secrets.decrypt(account.encryptedSecret());
    }

    public String callbackToken(String accountId) {
        String encrypted = jdbc.queryForObject("SELECT callback_token_encrypted FROM wechat_mp_account WHERE id=?", String.class, accountId);
        return encrypted == null || encrypted.isBlank() ? "" : secrets.decrypt(encrypted);
    }

    public String aesKey(String accountId) {
        String encrypted = jdbc.queryForObject("SELECT aes_key_encrypted FROM wechat_mp_account WHERE id=?", String.class, accountId);
        return encrypted == null || encrypted.isBlank() ? "" : secrets.decrypt(encrypted);
    }

    public String appId(String accountId) {
        Account account = requireAccount(accountId);
        if (account == null) throw new BusinessException(404, "公众号账号不存在");
        return account.appId();
    }

    private Account notificationAccount(WechatNotificationSender.Selection sender) {
        Account account = requireAccount(sender.accountId());
        if (account == null || !account.enabled() || !account.appId().equals(sender.appId()) ||
                !account.mode().equals(sender.managed() ? "OPEN_PLATFORM" : "DIRECT"))
            throw new BusinessException(409, "通知发送公众号已变更");
        return account;
    }

    public String notificationToken(WechatNotificationSender.Selection sender) throws Exception {
        return accessToken(notificationAccount(sender));
    }

    String directNotificationSecret(WechatNotificationSender.Selection sender) {
        if (!"DIRECT".equals(sender.status())) throw new BusinessException(409,"非直连发送公众号");
        return secrets.decrypt(notificationAccount(sender).encryptedSecret());
    }

    public String accessTokenOrNull() throws Exception {
        Account account = defaultAccount();
        return account == null ? null : accessToken(account);
    }

    public String accessToken(String accountId) throws Exception {
        Account account = requireAccount(accountId);
        if (account == null) throw new BusinessException(404, "公众号账号不存在");
        return accessToken(account);
    }

    public JSONObject apiGet(String accountId, String pathAndQuery) throws Exception {
        String token = accessToken(accountId);
        String separator = pathAndQuery.contains("?") ? "&" : "?";
        return get("https://api.weixin.qq.com" + pathAndQuery + separator + "access_token=" + encode(token));
    }

    public JSONObject apiPost(String accountId, String pathAndQuery, Object body) throws Exception {
        String token = accessToken(accountId);
        String separator = pathAndQuery.contains("?") ? "&" : "?";
        return post("https://api.weixin.qq.com" + pathAndQuery + separator + "access_token=" + encode(token), body);
    }

    public JSONObject uploadMaterial(String accountId, String type, String filename, String contentType, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw new BusinessException(400, "素材必须为 1 字节至 10MB");
        String normalized = Objects.toString(type, "").toLowerCase(Locale.ROOT);
        if (!Set.of("image", "voice", "video", "thumb").contains(normalized)) throw new BusinessException(400, "不支持的素材类型");
        String token = accessToken(accountId);
        String safeName = Objects.toString(filename, "material").replaceAll("[^A-Za-z0-9._-]", "_");
        String boundary = "----delta" + UUID.randomUUID().toString().replace("-", "");
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"media\"; filename=\"" + safeName +
                "\"\r\nContent-Type: " + Objects.toString(contentType, "application/octet-stream") + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8), tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headBytes.length + bytes.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(bytes, 0, body, headBytes.length, bytes.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + bytes.length, tailBytes.length);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/cgi-bin/material/add_material?access_token=" +
                        encode(token) + "&type=" + encode(normalized)))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 100_000) throw new BusinessException(502, "微信公众号素材服务暂时不可用");
        return JSON.parseObject(response.body());
    }

    public String exchangeOpenid(String code) throws Exception {
        Account account = defaultAccount();
        if (account == null) return null;
        String appId = account.appId(), secret = secrets.decrypt(account.encryptedSecret());
        if (secret.isBlank()) throw new BusinessException(503, "公众号凭据不完整，请联系管理员");
        String url = "https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + encode(appId) +
                "&secret=" + encode(secret) + "&code=" + encode(code) + "&grant_type=authorization_code";
        JSONObject result = get(url);
        String openid = result.getString("openid");
        if (openid == null || !openid.matches("[A-Za-z0-9_-]{10,128}"))
            throw new BusinessException(400, "微信授权未完成，请重新操作");
        return openid;
    }

    private Map<String, JSONObject> templates(String accountId) {
        Map<String, JSONObject> result = new HashMap<>();
        jdbc.query("SELECT role,scenario,template_id,fields_json,url,miniprogram_json,enabled FROM wechat_mp_template WHERE account_id=?",
                rs -> {
                    JSONObject row = templateRow(rs.getString("template_id"), rs.getString("fields_json"),
                            rs.getString("url"), rs.getString("miniprogram_json"));
                    row.put("enabled", rs.getBoolean("enabled"));
                    result.put(rs.getString("role") + ":" + rs.getString("scenario"), row);
                }, accountId);
        return result;
    }

    private JSONObject templateRow(String templateId, String fieldsJson, String url, String miniprogramJson) {
        JSONObject template = new JSONObject();
        template.put("templateId", templateId);
        template.put("fields", JSON.parseObject(fieldsJson));
        if (url != null && !url.isBlank()) template.put("url", url);
        if (miniprogramJson != null && !miniprogramJson.isBlank()) template.put("miniprogram", JSON.parseObject(miniprogramJson));
        return template;
    }

    private JSONObject validateFields(JSONObject fields) {
        if (fields == null || fields.isEmpty() || fields.size() > 20) throw new BusinessException(400, "请配置 1 到 20 个模板参数");
        Set<String> allowed = Set.of("thing", "character_string", "time", "amount", "phone_number", "car_number", "const");
        JSONObject normalized = new JSONObject();
        for (String key : fields.keySet()) {
            String name = key == null ? "" : key.trim();
            if (!name.matches("[A-Za-z_]+[0-9]*") || allowed.stream().noneMatch(name::startsWith))
                throw new BusinessException(400, "模板参数 " + name + " 不是微信官方支持的类型");
            String source = text(fields.get(key));
            if (source.isBlank() || source.length() > 64) throw new BusinessException(400, "模板参数 " + name + " 缺少系统数据源");
            normalized.put(name, source);
        }
        return normalized;
    }

    private static void applyMiniProgramValidation(JSONObject mini) {
        String appid = text(mini.getString("appid"));
        String pagepath = text(mini.getString("pagepath"));
        if (appid.isBlank() && pagepath.isBlank()) return;
        if (!appid.matches("[A-Za-z0-9_-]{2,64}") || pagepath.isBlank() || pagepath.length() > 200)
            throw new BusinessException(400, "小程序 AppID 和页面路径需要同时填写且格式正确");
    }

    private static void validateUrl(String value) {
        if (value.length() > 2000) throw new BusinessException(400, "模板跳转链接过长");
        try {
            URI uri = URI.create(value.trim());
            if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (Exception error) {
            throw new BusinessException(400, "模板跳转链接必须是有效的 HTTP/HTTPS 地址");
        }
    }

    private boolean callbackConfigured(String accountId) {
        String value = jdbc.queryForObject("SELECT callback_token_encrypted FROM wechat_mp_account WHERE id=?", String.class, accountId);
        return value != null && !value.isBlank();
    }

    private Account defaultAccount() {
        List<Account> rows = jdbc.query("SELECT id,name,app_id,app_secret_encrypted,account_mode,enabled,is_default " +
                        "FROM wechat_mp_account WHERE enabled=1 AND account_mode='DIRECT' ORDER BY is_default DESC, created_at ASC LIMIT 1",
                (rs, n) -> new Account(rs.getString("id"), rs.getString("name"), rs.getString("app_id"),
                        Objects.toString(rs.getString("app_secret_encrypted"), ""), rs.getString("account_mode"),
                        rs.getInt("enabled") == 1, rs.getInt("is_default") == 1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Account requireAccount(String id) {
        if (id == null || id.isBlank()) return null;
        List<Account> rows = jdbc.query("SELECT id,name,app_id,app_secret_encrypted,account_mode,enabled,is_default " +
                        "FROM wechat_mp_account WHERE id=?",
                (rs, n) -> new Account(rs.getString("id"), rs.getString("name"), rs.getString("app_id"),
                        Objects.toString(rs.getString("app_secret_encrypted"), ""), rs.getString("account_mode"),
                        rs.getInt("enabled") == 1, rs.getInt("is_default") == 1), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String accessToken(Account account) throws Exception {
        if (!account.enabled()) throw new BusinessException(403, "该公众号已停用");
        if ("OPEN_PLATFORM".equals(account.mode())) return openPlatform.accessToken(account.appId());
        String cacheKey = Objects.toString(com.delta.common.tenant.TenantContext.currentIdOrNull(), "") + ":" + account.id() + ":" + account.appId();
        AccessToken cached = accessTokens.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt()) return cached.value();
        if (account.encryptedSecret().isBlank()) throw new BusinessException(503, "公众号凭据不完整，请先配置 AppSecret");
        JSONObject result = post("https://api.weixin.qq.com/cgi-bin/stable_token",
                Map.of("grant_type", "client_credential", "appid", account.appId(), "secret", secrets.decrypt(account.encryptedSecret())));
        String token = result.getString("access_token");
        if (token == null || token.isBlank()) {
            updateError(account.id(), "WECHAT_TOKEN_UNAVAILABLE");
            throw new BusinessException(502, "公众号凭据校验失败，请检查 AppID、AppSecret 和接口权限");
        }
        accessTokens.put(cacheKey, new AccessToken(token, System.currentTimeMillis() + Math.max(60, result.getIntValue("expires_in") - 120) * 1000L));
        return token;
    }

    private void updateError(String accountId, String code) {
        try { jdbc.update("UPDATE wechat_mp_account SET last_checked_at=CURRENT_TIMESTAMP,last_error=? WHERE id=?",
                truncate(code, 160), accountId); } catch (Exception ignored) {}
    }

    private JSONObject get(String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 100_000)
            throw new BusinessException(502, "微信公众号服务暂时不可用");
        return JSON.parseObject(response.body());
    }

    private JSONObject post(String url, Object body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 100_000)
            throw new BusinessException(502, "微信公众号服务暂时不可用");
        return JSON.parseObject(response.body());
    }

    private static String canonicalRole(String value) {
        String type = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
        if ("GUEST".equals(type)) return "USER";
        if ("BOTH".equals(type)) return "ADMIN";
        return ROLES.contains(type) ? type : "USER";
    }

    private static String required(Object value, String message, int max) {
        String result = text(value);
        if (result.isBlank()) throw new BusinessException(400, message);
        if (result.length() > max) throw new BusinessException(400, message);
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
