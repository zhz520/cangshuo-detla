package com.delta.admin.controller;

import com.delta.admin.service.MembershipAdminService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Structured membership configuration for the administration console. */
@RestController
@RequestMapping("/admin/membership")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
@RequiredArgsConstructor
public class MembershipAdminController {
    private final MembershipAdminService membership;

    @GetMapping
    public R<Map<String, Object>> current() {
        return R.ok(membership.current());
    }

    @PutMapping
    @OpLog(module = "marketing", operation = "保存用户会员等级")
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        Object enabledValue = body.get("enabled");
        if (!(enabledValue instanceof Boolean enabled)) throw new BusinessException(400, "会员启用状态无效");
        Object tiersValue = body.get("tiers");
        if (!(tiersValue instanceof List<?>)) throw new BusinessException(400, "会员等级规则格式无效");
        return R.ok(membership.save(enabled, (List<?>) tiersValue));
    }
}
