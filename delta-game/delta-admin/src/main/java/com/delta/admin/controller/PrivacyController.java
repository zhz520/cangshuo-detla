package com.delta.admin.controller;

import com.delta.admin.service.PrivacyRequestService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PrivacyController {
    private final PrivacyRequestService privacy;

    public record Request(@NotBlank @Pattern(regexp = "ERASE_IDENTITY|CLOSE_ACCOUNT") String kind,
                          @Size(max = 500) String note) {}

    @GetMapping("/user/privacy/requests")
    public R<List<PrivacyRequestService.RequestView>> mine() {
        return R.ok(privacy.mine(SecurityUtils.getUserId()));
    }

    @PostMapping("/user/privacy/requests")
    @OpLog(module = "privacy", operation = "用户申请注销或删除实名资料")
    public R<Void> request(@Valid @RequestBody Request body) {
        privacy.request(SecurityUtils.getUserId(), body.kind(), body.note());
        return R.ok();
    }

    @GetMapping("/admin/privacy/requests")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('user.manage')")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String taskId) {
        return R.ok(privacy.list(taskId));
    }

    public record Decision(boolean approved, @NotBlank @Size(max = 500) String note) {}

    @PutMapping("/admin/privacy/requests/{id}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('user.manage')")
    @OpLog(module = "privacy", operation = "审核注销或实名删除申请")
    public R<Void> review(@PathVariable String id, @Valid @RequestBody Decision body) {
        privacy.review(id, body.approved(), body.note(), SecurityUtils.getUserId());
        return R.ok();
    }
}
