package com.delta.pay.config;

import com.delta.system.service.SysConfigService;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.service.payments.h5.H5Service;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.refund.RefundService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信支付动态配置及客户端管理。
 *
 * <p>后台保存配置后，SysConfigService 会清理 Redis 配置缓存；下一次请求发现配置签名变化
 * 后会自动重建微信支付客户端，不需要重启服务。</p>
 */
@Service
@Slf4j
public class WxPayConfiguration {
    private final SysConfigService sysConfigService;
    @Autowired(required = false)
    private TenantProperties tenantProperties;

    @Value("${wx.miniapp.appid:}")
    private String fallbackMiniAppId;
    @Value("${wx.pay.enabled:false}")
    private boolean fallbackEnabled;
    @Value("${wx.pay.mch-id:}")
    private String fallbackMchId;
    @Value("${wx.pay.notify-url:}")
    private String fallbackNotifyUrl;
    @Value("${wx.pay.api-v3-key:}")
    private String fallbackApiV3Key;
    @Value("${wx.pay.private-key-path:}")
    private String fallbackPrivateKeyPath;
    @Value("${wx.pay.cert-serial-no:}")
    private String fallbackCertSerialNo;
    @Value("${wx.pay.public-key-id:}")
    private String fallbackPublicKeyId;
    @Value("${wx.pay.public-key-path:}")
    private String fallbackPublicKeyPath;
    @Value("${wx.pay.h5-type:WAP}")
    private String fallbackH5Type;
    @Value("${wx.pay.h5-app-name:三角洲服务}")
    private String fallbackH5AppName;

    private final ConcurrentHashMap<String, CachedClients> cachedClients = new ConcurrentHashMap<>();

    public WxPayConfiguration(SysConfigService sysConfigService) {
        this.sysConfigService = sysConfigService;
    }

    public boolean isEnabled() {
        tenantScope();
        return loadConfig().enabled();
    }

    public RuntimeConfig getRuntimeConfig() {
        tenantScope();
        return loadConfig();
    }

    public CachedClients getClients() {
        String scope = tenantScope();
        RuntimeConfig config = loadConfig();
        // 关闭开关只禁止新支付，不影响已有订单的回调验签和退款。
        // 只有在没有任何可用凭据时才不创建客户端。
        if (!hasCredentials(config)) {
            if (config.enabled()) throw new com.delta.common.exception.BusinessException(503,
                    "微信支付商户证书缺失或无效，请在系统配置中重新上传商户私钥和微信支付公钥");
            return null;
        }
        CachedClients current = cachedClients.get(scope);
        if (current != null && current.getConfig().equals(config)) {
            return current;
        }
        synchronized (this) {
            current = cachedClients.get(scope);
            if (current != null && current.getConfig().equals(config)) {
                return current;
            }
            try {
                Config wxConfig = new RSAPublicKeyConfig.Builder()
                        .merchantId(config.mchId())
                        .privateKeyFromPath(config.privateKeyPath())
                        .merchantSerialNumber(config.certSerialNo())
                        .apiV3Key(config.apiV3Key())
                        .publicKeyId(config.publicKeyId())
                        .publicKeyFromPath(config.publicKeyPath())
                        .build();
                CachedClients created = new CachedClients(
                        config,
                        new JsapiServiceExtension.Builder().config(wxConfig).build(),
                        new H5Service.Builder().config(wxConfig).build(),
                        new RefundService.Builder().config(wxConfig).build(),
                        new NotificationParser((NotificationConfig) wxConfig)
                );
                cachedClients.put(scope, created);
                return created;
            } catch (RuntimeException error) {
                log.error("微信支付客户端初始化失败: {}", error.getClass().getSimpleName());
                throw new com.delta.common.exception.BusinessException(503,
                        "微信支付商户证书或微信支付公钥无效，请检查后重新上传");
            }
        }
    }

    private RuntimeConfig loadConfig() {
        boolean enabled = getBoolean("wx.pay.enabled", fallbackEnabled);
        String appId = get("wx.pay.appid", fallbackMiniAppId);
        return new RuntimeConfig(
                enabled,
                appId,
                get("wx.pay.mch-id", fallbackMchId),
                get("wx.pay.notify-url", fallbackNotifyUrl),
                get("wx.pay.api-v3-key", fallbackApiV3Key),
                get("wx.pay.private-key-path", fallbackPrivateKeyPath),
                get("wx.pay.cert-serial-no", fallbackCertSerialNo),
                get("wx.pay.public-key-id", fallbackPublicKeyId),
                get("wx.pay.public-key-path", fallbackPublicKeyPath),
                get("wx.pay.h5-type", fallbackH5Type),
                get("wx.pay.h5-app-name", fallbackH5AppName)
        );
    }

    private String get(String key, String fallback) {
        String value = sysConfigService.getConfigValue(key);
        if (value != null && !value.isBlank()) return value.trim();
        return sharedMode() && tenantCredential(key) ? "" : fallback;
    }

    private boolean getBoolean(String key, boolean fallback) {
        String value = sysConfigService.getConfigValue(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private boolean hasCredentials(RuntimeConfig config) {
        if (isBlank(config.mchId()) || isBlank(config.apiV3Key())
                || isBlank(config.privateKeyPath()) || isBlank(config.certSerialNo())
                || isBlank(config.publicKeyId()) || isBlank(config.publicKeyPath())) {
            return false;
        }
        try {
            return Files.isRegularFile(Path.of(config.privateKeyPath()))
                    && Files.isRegularFile(Path.of(config.publicKeyPath()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean sharedMode() { return tenantProperties != null && tenantProperties.isEnabled(); }

    private boolean tenantCredential(String key) {
        return Set.of("wx.pay.appid","wx.pay.mch-id","wx.pay.notify-url","wx.pay.api-v3-key",
                "wx.pay.private-key-path","wx.pay.cert-serial-no","wx.pay.public-key-id","wx.pay.public-key-path")
                .contains(key);
    }

    private String tenantScope() {
        if (!sharedMode()) return "default";
        return TenantContext.requireId();
    }

    public record RuntimeConfig(
            boolean enabled,
            String appId,
            String mchId,
            String notifyUrl,
            String apiV3Key,
            String privateKeyPath,
            String certSerialNo,
            String publicKeyId,
            String publicKeyPath,
            String h5Type,
            String h5AppName
    ) {}

    @Getter
    public static class CachedClients {
        private final RuntimeConfig config;
        private final JsapiServiceExtension jsapiService;
        private final H5Service h5Service;
        private final RefundService refundService;
        private final NotificationParser notificationParser;

        public CachedClients(RuntimeConfig config, JsapiServiceExtension jsapiService,
                             H5Service h5Service, RefundService refundService,
                             NotificationParser notificationParser) {
            this.config = Objects.requireNonNull(config);
            this.jsapiService = Objects.requireNonNull(jsapiService);
            this.h5Service = Objects.requireNonNull(h5Service);
            this.refundService = Objects.requireNonNull(refundService);
            this.notificationParser = Objects.requireNonNull(notificationParser);
        }
    }
}
