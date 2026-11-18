package com.delta.user.service;

import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class WebLoginPolicy {
    private final SysConfigService configs;

    public String primary() {
        return "WECHAT".equals(configs.getConfigValue("auth.primary_method", "PHONE").trim().toUpperCase(Locale.ROOT))
                ? "WECHAT" : "PHONE";
    }

    public boolean wechatConfigured() {
        return !configs.getConfigValue("wechat.official.app_id", "").isBlank()
                && !configs.getConfigValue("wechat.official.app_secret", "").isBlank()
                && !"********".equals(configs.getConfigValue("wechat.official.app_secret", "").trim());
    }

    public boolean wechatEnabled() {
        return enabled("auth.wechat.enabled", false) && wechatConfigured() && customerOrigin() != null;
    }

    public boolean phoneEnabled() {
        // Preserve the legacy switch, except where it would remove the only primary fallback.
        return "PHONE".equals(primary()) || !wechatEnabled() || enabled("auth.phone.enabled", true);
    }

    public boolean passwordEnabled() {
        return enabled("auth.password.enabled", true);
    }

    public String customerOrigin() {
        try {
            URI uri = URI.create(configs.getConfigValue("site.customer_web_url", "").trim());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))) return "https://" + uri.getRawAuthority();
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    private boolean enabled(String key, boolean fallback) {
        return "true".equalsIgnoreCase(configs.getConfigValue(key, Boolean.toString(fallback)));
    }
}
