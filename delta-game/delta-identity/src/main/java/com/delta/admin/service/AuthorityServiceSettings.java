package com.delta.admin.service;

import com.delta.common.exception.BusinessException;
import com.delta.common.sms.service.SmsGatewayClient;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;

/** Tenant-level SMS/identity preferences. Supplier secrets stay server-side and are never returned. */
@Service
@RequiredArgsConstructor
public class AuthorityServiceSettings {
    private static final String SMS = "sms.gateway.enabled";
    private static final String OTP = "sms.aliyun.otp_enabled";
    private static final String IDENTITY = "identity.auto.enabled";
    private static final String PROVIDER = "sms.provider.mode";
    private static final Set<String> REQUIRED_OTP_TEMPLATES = Set.of(
            "otp_login", "otp_reset", "otp_bind_old", "otp_bind_new", "otp_wechat_bind");

    private final SysConfigService config;
    private final JdbcTemplate jdbc;
    private final SmsGatewayClient sms;
    private final IdentityBillingClient identity;

    public record SmsSettings(String provider, boolean configured, boolean enabled, boolean otpEnabled,
                              boolean legacyActive, String endpoint, String accessKeyId, boolean hasAccessKeySecret,
                              String signName, String verificationTemplateCode, String playerFinishTemplateCode,
                              String csMessageTemplateCode, String teammateInvitedTemplateCode,
                              boolean authorityConfigured) {}

    public record IdentitySettings(String provider, boolean configured, boolean enabled, boolean otpEnabled,
                                   boolean legacyActive) {}

    public SmsSettings sms() {
        String provider = sms.provider();
        boolean enabled = sms.enabled();
        SmsGatewayClient.LegacySettings legacy = sms.legacySettings();
        boolean authorityConfigured = sms.configured();
        boolean configured = "ALIYUN".equals(provider) ? sms.legacyConfigured() : authorityConfigured;
        return new SmsSettings(provider, configured, enabled, enabled && flag(OTP), sms.legacyEnabled(),
                legacy.endpoint(), legacy.accessKeyId(), legacy.hasAccessKeySecret(), legacy.signName(),
                legacy.verificationTemplateCode(), legacy.playerFinishTemplateCode(),
                legacy.csMessageTemplateCode(), legacy.teammateInvitedTemplateCode(), authorityConfigured);
    }

    public IdentitySettings identity() {
        return new IdentitySettings("AUTHORITY", identity.configured(), flag(IDENTITY), false, false);
    }

    @Transactional
    public SmsSettings saveSms(String provider, boolean enabled, boolean otpEnabled, String endpoint,
                               String accessKeyId, String accessKeySecret, String signName,
                               String verificationTemplateCode, String playerFinishTemplateCode,
                               String csMessageTemplateCode, String teammateInvitedTemplateCode) {
        String mode = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTHORITY", "ALIYUN").contains(mode))
            throw new BusinessException(400, "请选择短信服务提供方");
        if (otpEnabled && !enabled)
            throw new BusinessException(400, "启用短信验证码前，请先启用短信服务");
        if ("AUTHORITY".equals(mode)) {
            saveAuthoritySms(enabled, otpEnabled);
        } else {
            saveAliyunSms(enabled, otpEnabled, endpoint, accessKeyId, accessKeySecret, signName,
                    verificationTemplateCode, playerFinishTemplateCode, csMessageTemplateCode, teammateInvitedTemplateCode);
        }
        return sms();
    }

    private void saveAuthoritySms(boolean enabled, boolean otpEnabled) {
        if (enabled && !sms.configured())
            throw new BusinessException(409, "请先接入授权中心短信服务");
        var account = enabled ? sms.account() : null;
        if (enabled && !account.getBooleanValue("ready"))
            throw new BusinessException(409, "授权中心尚未启用短信发送或回执服务");
        if (otpEnabled) {
            var templates = account.getJSONArray("templates");
            var present = new java.util.HashSet<String>();
            if (templates != null) {
                for (int i = 0; i < templates.size(); i++) {
                    var item = templates.getJSONObject(i);
                    if (item != null && item.getString("template_key") != null)
                        present.add(item.getString("template_key"));
                }
            }
            var missing = REQUIRED_OTP_TEMPLATES.stream().filter(key -> !present.contains(key)).sorted().toList();
            if (!missing.isEmpty())
                throw new BusinessException(409, "授权中心验证码模板未启用：" + String.join("、", missing)
                        + "。请到授权中心「短信服务 → 模板管理」点击「初始化验证码模板」。");
        }
        write(PROVIDER, "短信服务提供方", "短信配置", "text", "AUTHORITY");
        write(SMS, "启用授权中心短信服务", "短信配置", "boolean", Boolean.toString(enabled));
        write(OTP, "启用短信验证码", "短信配置", "boolean", Boolean.toString(otpEnabled));
        if (enabled) write("sms.gateway.managed", "已切换至授权中心短信", "短信配置", "boolean", "true");
    }

    private void saveAliyunSms(boolean enabled, boolean otpEnabled, String endpoint, String accessKeyId,
                               String accessKeySecret, String signName, String verificationTemplateCode,
                               String playerFinishTemplateCode, String csMessageTemplateCode,
                               String teammateInvitedTemplateCode) {
        SmsGatewayClient.LegacySettings current = sms.legacySettings();
        String nextEndpoint = blank(endpoint) ? current.endpoint() : endpoint.trim();
        if (!"dysmsapi.aliyuncs.com".equals(nextEndpoint))
            throw new BusinessException(400, "阿里云短信 Endpoint 只能使用 dysmsapi.aliyuncs.com");
        String nextAccessKeyId = blank(accessKeyId) ? current.accessKeyId() : accessKeyId.trim();
        boolean hasSecret = current.hasAccessKeySecret() || !blank(accessKeySecret);
        String nextSignName = blank(signName) ? current.signName() : signName.trim();
        String nextVerification = blank(verificationTemplateCode) ? current.verificationTemplateCode() : verificationTemplateCode.trim();
        String nextFinish = blank(playerFinishTemplateCode) ? current.playerFinishTemplateCode() : playerFinishTemplateCode.trim();
        String nextCs = blank(csMessageTemplateCode) ? current.csMessageTemplateCode() : csMessageTemplateCode.trim();
        String nextInvite = blank(teammateInvitedTemplateCode) ? current.teammateInvitedTemplateCode() : teammateInvitedTemplateCode.trim();
        if (enabled && (blank(nextAccessKeyId) || !hasSecret || blank(nextSignName)))
            throw new BusinessException(409, "启用阿里云短信前请填写 AccessKey ID、Secret 和短信签名");
        if (otpEnabled && blank(nextVerification))
            throw new BusinessException(409, "启用短信验证码前请填写验证码模板 Code");
        write(PROVIDER, "短信服务提供方", "短信配置", "text", "ALIYUN");
        write("sms.aliyun.enabled", "启用阿里云短信", "短信配置", "boolean", Boolean.toString(enabled));
        write("sms.aliyun.access_key_id", "阿里云 AccessKey ID", "短信配置", "text", nextAccessKeyId);
        if (!blank(accessKeySecret))
            write("sms.aliyun.access_key_secret", "阿里云 AccessKey Secret", "短信配置", "text", accessKeySecret.trim());
        write("sms.aliyun.endpoint", "阿里云短信 Endpoint", "短信配置", "text", nextEndpoint);
        write("sms.aliyun.sign_name", "阿里云短信签名", "短信配置", "text", nextSignName);
        write("sms.aliyun.template_code.verification", "短信验证码模板 Code", "短信配置", "text", nextVerification);
        write("sms.aliyun.template_code.player_finish_order", "通知老板结单模板 Code", "短信配置", "text", nextFinish);
        write("sms.aliyun.template_code.cs_message_reminder", "消息提醒模板 Code", "短信配置", "text", nextCs);
        write("sms.aliyun.template_code.player_teammate_invited", "打手被邀请模板 Code", "短信配置", "text", nextInvite);
        write(OTP, "启用短信验证码", "短信配置", "boolean", Boolean.toString(otpEnabled));
    }

    @Transactional
    public IdentitySettings saveIdentity(boolean enabled) {
        if (enabled && !identity.configured())
            throw new BusinessException(409, "请先接入授权中心实名服务");
        if (enabled && !identity.account().path("ready").asBoolean())
            throw new BusinessException(409, "请先在授权中心启用实名供应商");
        write(IDENTITY, "启用自动二要素实名", "实名配置", "boolean", Boolean.toString(enabled));
        return identity();
    }

    private boolean flag(String key) { return "true".equalsIgnoreCase(config.getConfigValue(key, "false")); }

    private void write(String key, String name, String group, String valueType, String value) {
        jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group) " +
                "VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE config_value=?",
                key, name, value, valueType, group, value);
        // The save response re-reads the setting inside the same transaction; clear the
        // stale cache immediately and again after commit for readers racing the commit.
        config.evictCache(key);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { config.evictCache(key); }
        });
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
