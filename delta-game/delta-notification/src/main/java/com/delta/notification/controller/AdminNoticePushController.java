package com.delta.notification.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.notification.service.NoticePushService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/system/notice")
@RequiredArgsConstructor
public class AdminNoticePushController {
    private final NoticePushService notices;

    @OpLog(module = "system", operation = "推送公告")
    @PostMapping("/{id}/push")
    public R<Map<String, Integer>> push(@PathVariable Long id) {
        return R.ok(notices.push(id));
    }
}
