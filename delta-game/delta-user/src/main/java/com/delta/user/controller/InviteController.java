package com.delta.user.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.user.service.InviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/user/invites")
@RequiredArgsConstructor
public class InviteController {
    private final InviteService invites;

    @GetMapping
    public R<Map<String, Object>> info() {
        return R.ok(invites.info(SecurityUtils.getUserId()));
    }

    @PostMapping("/bind")
    public R<Void> bind(@RequestBody Map<String, String> body) {
        invites.bind(SecurityUtils.getUserId(), body == null ? null : body.get("code"));
        return R.ok();
    }

    /** Kept for login/registration attribution callers. */
    public void bindCode(Long userId, String code) {
        invites.bind(userId, code);
    }
}
