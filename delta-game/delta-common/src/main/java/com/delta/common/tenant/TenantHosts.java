package com.delta.common.tenant;

import java.net.IDN;
import java.util.Locale;

/** Normalizes registered and request host names without trusting arbitrary headers. */
public final class TenantHosts {
    private TenantHosts() {
    }

    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("域名为空");
        String value = raw.trim();
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.isBlank() || value.length() > 253 || value.startsWith(".")) {
            throw new IllegalArgumentException("域名格式无效");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('@') >= 0
                || value.indexOf(':') >= 0 || value.indexOf('*') >= 0
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("域名不得包含端口、路径、通配符或协议");
        }
        try {
            String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.length() > 253) throw new IllegalArgumentException("域名过长");
            for (String label : ascii.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63
                        || label.startsWith("-") || label.endsWith("-")) {
                    throw new IllegalArgumentException("域名标签格式无效");
                }
            }
            return ascii;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("域名格式无效", error);
        }
    }
}
