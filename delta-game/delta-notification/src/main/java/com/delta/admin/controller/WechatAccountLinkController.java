package com.delta.admin.controller;

import com.delta.common.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Retired: old clients must use the verification ticket and explicit confirmation. */
@RestController
public class WechatAccountLinkController {
    @PostMapping("/user/auth/wechat/link-account")
    public R<Void> retired() {
        return R.fail(409, "请刷新页面，重新微信授权后绑定手机号");
    }
}
