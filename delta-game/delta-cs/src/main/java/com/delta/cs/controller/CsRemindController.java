package com.delta.cs.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.cs.service.CsRemindService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 客服端提醒红点：投诉未读、接力待审核、消息未读、系统通知未读 */
@RestController
@RequestMapping("/cs/remind")
@RequiredArgsConstructor
public class CsRemindController {
    private final CsRemindService reminds;

    @GetMapping
    public R<Map<String, Object>> remind() {
        return R.ok(reminds.remind(SecurityUtils.getUserId()));
    }
}
