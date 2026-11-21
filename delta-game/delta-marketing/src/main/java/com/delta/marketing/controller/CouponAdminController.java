package com.delta.marketing.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.marketing.service.CouponAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/coupons")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
@RequiredArgsConstructor
public class CouponAdminController {
    private final CouponAdminService coupons;

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(coupons.list());
    }

    @GetMapping("/records")
    public R<List<Map<String, Object>>> records() {
        return R.ok(coupons.records());
    }

    @PostMapping
    @OpLog(module = "marketing", operation = "创建优惠券模板")
    public R<Void> create(@RequestBody Map<String, Object> body) {
        coupons.create(body);
        return R.ok();
    }

    @PutMapping("/{id}")
    @OpLog(module = "marketing", operation = "修改优惠券模板")
    public R<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        coupons.update(id, body);
        return R.ok();
    }
}
