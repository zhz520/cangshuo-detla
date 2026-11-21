package com.delta.workforce.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.security.service.StaffPermissions;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.workforce.service.StaffRoleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StaffRoleController {
    private final StaffRoleService roles;
    private final StaffPermissions permissions;

    @GetMapping("/cs/permissions")
    public R<List<String>> mine() {
        return R.ok(permissions.forStaff(SecurityUtils.getUserId(), SecurityUtils.getUserType()));
    }

    @GetMapping("/system/roles")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<Map<String, Object>> list() {
        return R.ok(roles.list());
    }

    public record RoleBody(@NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,63}") String code,
                           @NotBlank @Size(max = 100) String name,
                           @NotNull @Size(max = 30) List<String> permissions,
                           boolean enabled) {}

    @PutMapping("/system/roles")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "roles", operation = "配置角色及退款权限")
    public R<Void> save(@Valid @RequestBody RoleBody body) {
        roles.save(body.code(), body.name(), body.permissions(), body.enabled());
        return R.ok();
    }

    @PutMapping("/system/roles/members/{id}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "roles", operation = "分配员工权限角色")
    public R<Void> assign(@PathVariable Long id, @RequestBody Map<String, String> body) {
        roles.assign(id, body.get("roleCode"), SecurityUtils.getUserId());
        return R.ok();
    }

    @DeleteMapping("/system/roles/{code}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "roles", operation = "删除权限角色")
    public R<Void> delete(@PathVariable String code) {
        roles.delete(code);
        return R.ok();
    }
}
