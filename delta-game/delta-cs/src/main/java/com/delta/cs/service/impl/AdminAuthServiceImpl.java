package com.delta.cs.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.constant.CacheConstants;
import com.delta.common.enums.UserTypeEnum;
import com.delta.common.exception.BusinessException;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.service.TokenService;
import com.delta.common.security.service.VerificationService;
import com.delta.common.security.utils.AdminRolePolicy;
import com.delta.cs.dto.AdminLoginRequest;
import com.delta.cs.dto.AdminLoginResponse;
import com.delta.cs.service.AdminAuthService;
import com.delta.system.entity.Admin;
import com.delta.system.entity.OperationLog;
import com.delta.system.service.AdminService;
import com.delta.system.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {
    private static final long LOGIN_FAILURE_WINDOW_MINUTES = 15L;
    private static final String LOGIN_FAILED = "账号或密码错误";
    private static final String DUMMY_PASSWORD_HASH = "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    private final AdminService adminService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final OperationLogService operationLogService;
    private final RedisService redisService;
    private final com.delta.common.security.service.RateLimitService limits;
    private final VerificationService verification;

    @Override
    public AdminLoginResponse login(AdminLoginRequest request) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        if(username.isEmpty() || username.length()>64 || request.getPassword()==null || request.getPassword().length()>128)
            throw new BusinessException(400,LOGIN_FAILED);
        var attrs=(ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        String remoteAddress=attrs==null?"unknown":attrs.getRequest().getRemoteAddr();
        limits.check("admin-login-ip:"+remoteAddress,30,900);
        limits.check("admin-login-name:"+username.toLowerCase(java.util.Locale.ROOT),50,900);
        verification.verifyAdminLoginCaptcha(request.getCaptchaId(),request.getCaptchaCode(),remoteAddress);
        LocalDateTime now = LocalDateTime.now();
        Admin admin = adminService.getOne(new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, username));
        if (admin == null || !Integer.valueOf(1).equals(admin.getStatus())) {
            passwordEncoder.matches(request.getPassword(), DUMMY_PASSWORD_HASH);
            throw new BusinessException(LOGIN_FAILED);
        }
        String failKey = buildFailKey(admin.getId());
        if (admin.getLockTime() != null || safeFailCount(admin) > 0) {
            clearLockState(admin, failKey, false);
        }
        if (!passwordEncoder.matches(request.getPassword(), admin.getPassword())) {
            handleLoginFailure(admin, failKey);
        }
        // The account role is authoritative. Ignore legacy client-side role
        // selectors so a cached page cannot lock a valid account out.
        String effectiveRole = AdminRolePolicy.effectiveRole(admin.getRole());
        clearLockState(admin, failKey, true);
        UserTypeEnum userType = "CS".equals(effectiveRole) ? UserTypeEnum.CS : UserTypeEnum.ADMIN;
        String token = tokenService.createToken(admin.getId(), userType);
        admin.setLastLoginAt(now);
        adminService.updateById(admin);
        // 记录登录日志到operation_log
        recordLoginLog(admin, effectiveRole);
        AdminLoginResponse resp = new AdminLoginResponse();
        resp.setToken(token);
        resp.setAdminId(admin.getId());
        resp.setNickname(admin.getNickname());
        resp.setAvatar(admin.getAvatar());
        resp.setRole(effectiveRole);
        return resp;
    }

    private void handleLoginFailure(Admin admin, String failKey) {
        long failCount = incrementFailCount(failKey);
        int currentFailCount = (int)Math.min(failCount, Integer.MAX_VALUE);
        admin.setLoginFailCount(currentFailCount);
        admin.setLockTime(null);
        adminService.updateById(admin);
        throw new BusinessException(LOGIN_FAILED);
    }

    private long incrementFailCount(String failKey) {
        Long failCount = redisService.increment(failKey);
        if (failCount == null) {
            throw new BusinessException("登录失败次数统计异常，请稍后重试");
        }
        if (failCount == 1L) {
            redisService.expire(failKey, LOGIN_FAILURE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
        return failCount;
    }

    private void clearLockState(Admin admin, String failKey, boolean clearLockTime) {
        admin.setLoginFailCount(0);
        if (clearLockTime) {
            admin.setLockTime(null);
        } else if (admin.getLockTime() != null && !admin.getLockTime().isAfter(LocalDateTime.now())) {
            admin.setLockTime(null);
        }
        redisService.delete(failKey);
    }

    private int safeFailCount(Admin admin) {
        return admin.getLoginFailCount() == null ? 0 : admin.getLoginFailCount();
    }

    private String buildFailKey(Long adminId) {
        return CacheConstants.LOGIN_FAIL_KEY + "admin:" + adminId;
    }

    private void recordLoginLog(Admin admin, String role) {
        try {
            OperationLog opLog = new OperationLog();
            opLog.setModule("auth");
            opLog.setOperation("login");
            opLog.setOperatorType(role);
            opLog.setOperatorId(admin.getId());
            opLog.setOperatorName(admin.getNickname());
            opLog.setTargetType("admin");
            opLog.setTargetId(admin.getId());
            opLog.setDetail(admin.getUsername() + " 以 " + role + " 角色登录");
            // 获取请求IP
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                opLog.setIp(attrs.getRequest().getRemoteAddr());
            }
            opLog.setCreatedAt(LocalDateTime.now());
            operationLogService.save(opLog);
        } catch (Exception e) {
            log.warn("记录登录日志失败", e);
        }
    }
}
