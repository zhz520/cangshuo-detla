package com.delta.common.tenant;

import com.delta.common.exception.BusinessException;

import java.util.regex.Pattern;

/** Validation shared by configuration, request lookup and thread context. */
public final class TenantIds {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private TenantIds() {
    }

    public static String requireValid(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("租户标识必须为1至48位小写字母、数字或连字符");
        }
        return value;
    }

    public static String requireContext(String value) {
        try {
            return requireValid(value);
        } catch (IllegalArgumentException error) {
            throw new BusinessException(503, "租户上下文不可用，请联系维护人员");
        }
    }
}
