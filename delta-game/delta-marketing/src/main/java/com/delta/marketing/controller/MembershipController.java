package com.delta.marketing.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.marketing.service.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/user/membership")
@RequiredArgsConstructor
public class MembershipController {
    private final MembershipService membership;

    @GetMapping
    public R<Map<String, Object>> current() {
        return R.ok(membership.current(SecurityUtils.getUserId()));
    }
}
