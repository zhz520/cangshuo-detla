package com.delta.marketing.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.marketing.service.InviteAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Operations view for referral attribution, rewards and recoverable debts. */
@RestController
@RequestMapping("/admin/invites")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
@RequiredArgsConstructor
public class InviteAdminController {
    private final InviteAdminService invites;

    @GetMapping
    public R<Map<String, Object>> list() {
        return R.ok(invites.list());
    }

    @PutMapping("/debts/{id}/recovered")
    @OpLog(module = "marketing", operation = "标记邀请奖励欠款已追回")
    public R<Void> markDebtRecovered(@PathVariable Long id) {
        invites.markDebtRecovered(id);
        return R.ok();
    }
}
