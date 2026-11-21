package com.delta.governance.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.governance.service.GovernanceAdminService;
import com.delta.player.service.PlayerGovernanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class GovernanceController {
    private final PlayerGovernanceService governance;
    private final GovernanceAdminService admin;

    @GetMapping("/player/invitations/{token}")
    public R<Map<String, Object>> invite(@PathVariable String token) {
        Map<String, Object> source = governance.invitation(token);
        return R.ok(Map.of("name", source.get("name"), "oneDevice", source.get("one_device")));
    }

    @GetMapping("/player/homepage/revisions")
    public R<List<Map<String, Object>>> history() {
        return R.ok(governance.history(SecurityUtils.getUserId()));
    }

    @GetMapping("/cs/governance/revisions")
    @PreAuthorize("@staffPermissions.allowed('media.review')")
    public R<List<Map<String, Object>>> revisions(@RequestParam(required = false) String taskId) {
        return R.ok(admin.revisions(taskId));
    }

    public record Review(boolean approved, @Size(max = 500) String note, boolean violation) {}

    @PutMapping("/cs/governance/revisions/{id}")
    @PreAuthorize("@staffPermissions.allowed('media.review')")
    @OpLog(module = "media", operation = "人工审核打手主页资料")
    public R<Void> review(@PathVariable String id, @Valid @RequestBody Review body) {
        governance.review(id, body.approved(), body.note(), body.violation());
        return R.ok();
    }

    @PostMapping("/cs/governance/revisions/{id}/recheck")
    @PreAuthorize("@staffPermissions.allowed('media.review')")
    @OpLog(module = "media", operation = "重新提交系统风控检测")
    public R<Void> recheck(@PathVariable String id) {
        governance.recheck(id);
        return R.ok();
    }

    @GetMapping("/cs/governance/invitations")
    @PreAuthorize("@staffPermissions.allowed('player.manage')")
    public R<List<Map<String, Object>>> invitations() {
        return R.ok(admin.invitations());
    }

    public record InvitationBody(@NotBlank @Size(max = 100) String name, boolean oneDevice, LocalDateTime expiresAt) {}

    @PostMapping("/cs/governance/invitations")
    @PreAuthorize("@staffPermissions.allowed('player.manage')")
    @OpLog(module = "invitation", operation = "创建入驻邀请链接")
    public R<String> createInvite(@Valid @RequestBody InvitationBody body) {
        return R.ok(admin.createInvite(body.name(), body.oneDevice(), body.expiresAt(), SecurityUtils.getUserId()));
    }

    @PutMapping("/cs/governance/invitations/{token}")
    @PreAuthorize("@staffPermissions.allowed('player.manage')")
    @OpLog(module = "invitation", operation = "启用或停用入驻邀请链接")
    public R<Void> toggle(@PathVariable String token, @RequestBody Map<String, Boolean> body) {
        admin.toggle(token, Boolean.TRUE.equals(body.get("enabled")));
        return R.ok();
    }

    @GetMapping("/cs/governance/shifts")
    public R<List<Map<String, Object>>> shifts() {
        return R.ok(admin.shifts());
    }

    @PutMapping("/cs/governance/shifts/{id}")
    @OpLog(module = "shift", operation = "员工上下班操作")
    public R<Void> shift(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        if (!id.equals(SecurityUtils.getUserId()) && !"ADMIN".equals(SecurityUtils.getUserType())) {
            throw new BusinessException(403, "客服只能操作自己的上下班状态");
        }
        admin.shift(id, Boolean.TRUE.equals(body.get("online")));
        return R.ok();
    }
}
