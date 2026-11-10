package com.delta.common.tenant;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in settings for a shared service group. The default remains a single-tenant
 * deployment, so existing customer installations keep their current connection and
 * Redis key behavior until the shared environment is explicitly configured.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tenant")
public class TenantProperties {
    private boolean enabled;
    private Map<String, Tenant> tenants = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Tenant {
        private boolean active = true;
        private List<String> hosts = new ArrayList<>();
        private String storageRoot = "";

        // Used by the data-source router. Values must be supplied through the
        // deployment's protected environment, never committed in this file.
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        private int maximumPoolSize = 8;
        private int minimumIdle;
        private long connectionTimeoutMs = 5_000L;
    }
}
