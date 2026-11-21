package com.delta.marketing.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.marketing.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user/coupons")
@RequiredArgsConstructor
public class CouponController {
    private final CouponService coupons;

    @GetMapping("/available")
    public R<List<Map<String, Object>>> available() {
        return R.ok(coupons.available());
    }

    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine() {
        return R.ok(coupons.mine(SecurityUtils.getUserId()));
    }

    @PostMapping("/{templateId}/claim")
    public R<Void> claim(@PathVariable Long templateId) {
        coupons.claim(templateId, SecurityUtils.getUserId());
        return R.ok();
    }
}
