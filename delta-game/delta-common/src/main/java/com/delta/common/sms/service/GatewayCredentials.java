package com.delta.common.sms.service;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** Operational credentials are bound to the platform UUID and never stored in tenant-editable settings. */
@Component
public class GatewayCredentials {
    public String secret(String instanceId) {
        return System.getenv().getOrDefault(
                "AUTHORITY_GATEWAY_SECRET_" + instanceId.replace("-", "_").toUpperCase(Locale.ROOT), "").trim();
    }
}
