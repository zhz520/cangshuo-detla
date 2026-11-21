package com.delta.governance.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.governance.service.DisplayPrivacyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** User-controlled visibility for the two public display surfaces. */
@RestController
@RequestMapping("/user/display/privacy")
@RequiredArgsConstructor
public class DisplayPrivacyController {
    private final DisplayPrivacyService privacy;

    @GetMapping
    public R<Map<String, Object>> current() {
        return R.ok(privacy.current(SecurityUtils.getUserId()));
    }

    @PutMapping
    public R<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        return R.ok(privacy.update(SecurityUtils.getUserId(), body));
    }
}
