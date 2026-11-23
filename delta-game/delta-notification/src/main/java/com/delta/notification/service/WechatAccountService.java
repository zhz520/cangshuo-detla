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

    private final JdbcTemplate jdbc;
    private final SecretCipher secrets;
    private final PlatformService platform;
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
    public void deleteAccount(String id) {
        Account account = requireAccount(id);
        if (account == null) throw new BusinessException(404, "公众号账号不存在");
        jdbc.update("DELETE FROM wechat_mp_template WHERE account_id=?", id);
        jdbc.update("DELETE FROM wechat_mp_template_catalog WHERE account_id=?", id);
        jdbc.update("DELETE FROM wechat_mp_account WHERE id=?", id);
        Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM wechat_mp_account", Integer.class);
        if (remaining != null && remaining > 0) {
            String next = jdbc.queryForObject("SELECT id FROM wechat_mp_account ORDER BY created_at ASC LIMIT 1", String.class);
            if (next != null) jdbc.update("UPDATE wechat_mp_account SET is_default=1 WHERE id=?", next);
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
        Map<String, JSONObject> configured = templates(accountId);
        List<Map<String, Object>> templates = new ArrayList<>();
        for (String role : ROLES) for (String scenario : SCENARIOS) {
            JSONObject template = configured.get(role + ":" + scenario);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", role);
            row.put("scenario", scenario);
            row.put("configured", template != null);
            row.put("templateId", template == null ? "" : template.getString("templateId"));
            row.put("fields", template == null ? new JSONObject() : template.getJSONObject("fields"));
            row.put("url", template == null ? "" : Objects.toString(template.getString("url"), ""));
            row.put("miniprogram", template == null ? new JSONObject() : template.getJSONObject("miniprogram"));
            row.put("status", template != null ? "READY" : "TEMPLATE_MISSING");
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
        result.put("ready", account.enabled() && !account.encryptedSecret().isBlank()
                && templates.stream().allMatch(row -> Boolean.TRUE.equals(row.get("configured"))));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveTemplate(String accountId, Map<String, Object> body) {
        requireAccount(accountId);
        String role = text(body.get("role")).toUpperCase(Locale.ROOT);
        String scenario = text(body.get("scenario")).toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) throw new BusinessException(400, "通知角色不正确");
        if (!SCENARIOS.contains(scenario)) throw new BusinessException(400, "通知场景不正确");
        String templateId = required(body.get("templateId"), "请选择或填写微信模板 ID", 128);
        if (!templateId.matches("[A-Za-z0-9_-]{6,128}")) throw new BusinessException(400, "微信模板 ID 格式不正确");
        JSONObject fields = body.get("fields") instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(body.get("fields")));
        if (fields == null || fields.isEmpty()) {
            Map<String, Object> authority = authorityScheme(role, scenario);
            if (authority != null && authority.get("fields") instanceof Map<?, ?> map)
                fields = JSON.parseObject(JSON.toJSONString(map));
        }
        fields = validateFields(fields);
        String url = text(body.get("url"));
        if (!url.isBlank()) validateUrl(url);
        JSONObject mini = null;
        if (body.get("miniprogram") instanceof Map<?, ?> map && !map.isEmpty()) {
            mini = JSON.parseObject(JSON.toJSONString(map));
            applyMiniProgramValidation(mini);
        }
        String source = "AUTHORITY".equalsIgnoreCase(text(body.get("source"))) ? "AUTHORITY" : "LOCAL";
        boolean enabled = body.get("enabled") == null || bool(body.get("enabled"));
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
        if (account == null || !account.enabled()) return null;
        String role = canonicalRole(userType);
        List<JSONObject> templates = jdbc.query("SELECT template_id,fields_json,url,miniprogram_json FROM wechat_mp_template " +
                        "WHERE account_id=? AND role=? AND scenario=? AND enabled=1",
                (rs, n) -> templateRow(rs.getString("template_id"), rs.getString("fields_json"),
                        rs.getString("url"), rs.getString("miniprogram_json")),
                account.id(), role, scenario);
        return templates.isEmpty() ? null : templates.get(0);
    }

    public List<Map<String, Object>> authoritySchemes() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            JsonNode project = platform.projectSnapshot();
            JsonNode schemes = project == null ? null : project.path("wechatTemplates").path("schemes");
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
        String role = canonicalRole(userType);
        Account account = defaultAccount();
        if (account == null) return SCENARIOS;
        Set<String> configured = new HashSet<>(jdbc.queryForList(
                "SELECT scenario FROM wechat_mp_template WHERE account_id=? AND role=? AND enabled=1",
                String.class, account.id(), role));
        return SCENARIOS.stream().filter(scenario -> !configured.contains(scenario)).toList();
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
        jdbc.query("SELECT role,scenario,template_id,fields_json,url,miniprogram_json FROM wechat_mp_template WHERE account_id=? AND enabled=1",
                rs -> {
                    result.put(rs.getString("role") + ":" + rs.getString("scenario"),
                            templateRow(rs.getString("template_id"), rs.getString("fields_json"),
                                    rs.getString("url"), rs.getString("miniprogram_json")));
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
                        "FROM wechat_mp_account WHERE enabled=1 ORDER BY is_default DESC, created_at ASC LIMIT 1",
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
        String cacheKey = account.id() + ":" + account.appId();
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
