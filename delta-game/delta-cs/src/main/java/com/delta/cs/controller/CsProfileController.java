package com.delta.cs.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.PasswordPolicy;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.cs.dto.AdminPasswordChangeRequest;
import com.delta.system.entity.Admin;
import com.delta.system.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 客服/管理员个人资料：改名字、换头像和修改本人密码
 */
@RestController
@RequestMapping("/cs/profile")
@RequiredArgsConstructor
public class CsProfileController {
    private final AdminService adminService;
    private final PasswordEncoder passwordEncoder;

    /** 获取当前客服资料 */
    @GetMapping
    public R<Admin> getProfile() {
        Long adminId = SecurityUtils.getUserId();
        Admin admin = adminService.getById(adminId);
        if (admin == null) return R.fail("用户不存在");
        admin.setPassword(null);
        return R.ok(admin);
    }

    /** 更新昵称和头像 */
    @PutMapping
    public R<Void> updateProfile(@RequestBody Map<String, String> body) {
        Long adminId = SecurityUtils.getUserId();
        Admin admin = adminService.getById(adminId);
        if (admin == null) return R.fail("用户不存在");
        if (body.containsKey("nickname")) admin.setNickname(body.get("nickname"));
        if (body.containsKey("avatar")) admin.setAvatar(body.get("avatar"));
        adminService.updateById(admin);
        return R.ok();
    }

    /** 修改当前登录员工的密码；数据库密码哈希变化后，旧登录令牌会立即失效。 */
    @OpLog(module = "staff", operation = "修改本人登录密码")
    @PutMapping("/password")
    public R<Void> changePassword(@RequestBody AdminPasswordChangeRequest body) {
        Long adminId = SecurityUtils.getUserId();
        Admin admin = adminService.getById(adminId);
        if (admin == null) throw new BusinessException(404, "用户不存在");
        if (!StringUtils.hasText(body.oldPassword()) || !StringUtils.hasText(body.newPassword())
                || !StringUtils.hasText(body.confirmation())) {
            throw new BusinessException(400, "请完整填写原密码、新密码和确认密码");
        }
        if (!passwordEncoder.matches(body.oldPassword(), admin.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }
        if (!body.newPassword().equals(body.confirmation())) {
            throw new BusinessException(400, "两次输入的新密码不一致");
        }
        PasswordPolicy.validate(body.newPassword());
        if (passwordEncoder.matches(body.newPassword(), admin.getPassword())) {
            throw new BusinessException(400, "新密码不能与原密码相同");
        }
        Admin update = new Admin();
        update.setId(adminId);
        update.setPassword(passwordEncoder.encode(body.newPassword()));
        update.setLoginFailCount(0);
        update.setLockTime(null);
        adminService.updateById(update);
        return R.ok();
    }
}
