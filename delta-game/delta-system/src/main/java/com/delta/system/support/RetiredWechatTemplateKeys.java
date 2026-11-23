package com.delta.system.support;

import com.delta.common.exception.BusinessException;

/** Historical sys_config rows are retained, but are no longer a template configuration source. */
public final class RetiredWechatTemplateKeys {
    public static final String EVENT = "wechat.official.template.";
    public static final String ROLE = "wechat.official.templates.";

    private RetiredWechatTemplateKeys() {}

    public static boolean matches(String key) {
        return key != null && (key.regionMatches(true, 0, EVENT, 0, EVENT.length())
                || key.regionMatches(true, 0, ROLE, 0, ROLE.length()));
    }

    public static void rejectWrite(String key) {
        if (matches(key)) throw new BusinessException(409, "旧版公众号模板配置已停用，请使用公众号模板管理");
    }
}
