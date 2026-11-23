package com.delta.order.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.order.service.FlashSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class FlashSaleController {
    private final FlashSaleService service;

    @GetMapping("/product/flash-sale/active")
    public R<Map<String, Object>> active(@RequestParam Long productId) {
        Long userId = null;
        try { userId = SecurityUtils.getUserId(); } catch (BusinessException ignored) {}
        return R.ok(service.activeForProduct(productId, userId));
    }

    @GetMapping("/admin/flash-sales")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> list() {
        return R.ok(service.list());
    }

    @PostMapping("/admin/flash-sales")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "flash-sale", operation = "新增秒杀活动")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.save(null, body));
    }

    @PutMapping("/admin/flash-sales/{id}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "flash-sale", operation = "编辑秒杀活动")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.ok(service.save(id, body));
    }

    @DeleteMapping("/admin/flash-sales/{id}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "flash-sale", operation = "删除秒杀活动")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
