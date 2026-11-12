package com.delta.admin.controller;

import com.delta.common.domain.R;
import com.delta.common.enums.UserTypeEnum;
import com.delta.common.security.service.TokenService;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.user.dto.LoginResponse;
import com.delta.user.entity.User;
import com.delta.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/auth/wechat")
@RequiredArgsConstructor
public class WechatAccountLinkController {
    private final UserService users;
    private final JdbcTemplate jdbc;
    private final com.delta.common.security.service.VerificationService verification;
    private final TokenService tokens;

    public record LinkRequest(@NotBlank @Pattern(regexp="^1\\d{10}$") String phone,
                              @NotBlank String requestId, @NotBlank String code) {}

    @PostMapping("/link-account")
    @Transactional(rollbackFor = Exception.class)
    public R<LoginResponse> link(@Valid @RequestBody LinkRequest body) {
        Long currentId = SecurityUtils.getUserId();
        User current = users.getById(currentId);
        if (current == null) return R.fail(401, "登录状态已失效");
        verification.verify(body.phone(), "BIND_NEW", String.valueOf(currentId), body.requestId(), body.code());
        User existing = users.getByPhone(body.phone());
        if (existing == null) return R.fail(404, "该手机号尚未注册，请使用普通绑定");
        if (existing.getId().equals(currentId)) return R.ok(response(existing));
        if (!Integer.valueOf(1).equals(existing.getStatus())) return R.fail(403, "该手机号账号暂不可用");
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE user_id=?", Integer.class, currentId);
        Integer payments = jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE user_id=?", Integer.class, currentId);
        Integer otherBindings = jdbc.queryForObject("SELECT COUNT(*) FROM wechat_official_binding WHERE user_type='USER' AND user_id=?", Integer.class, existing.getId());
        java.math.BigDecimal balance = jdbc.queryForObject("SELECT COALESCE(balance,0) FROM wallet WHERE user_id=?", java.math.BigDecimal.class, currentId);
        if ((orders != null && orders > 0) || (payments != null && payments > 0)
                || (balance != null && balance.signum() != 0) || (otherBindings != null && otherBindings > 0))
            return R.fail(409, "该微信或手机账号已有业务数据，不能自动合并，请联系客服");
        jdbc.update("UPDATE wechat_official_binding SET user_id=? WHERE user_type='USER' AND user_id=?",
                existing.getId(), currentId);
        jdbc.update("UPDATE `user` SET openid=?,status=0,deleted=1 WHERE id=?",
                "merged:" + currentId, currentId);
        return R.ok(response(existing));
    }

    private LoginResponse response(User user) {
        LoginResponse response = new LoginResponse();
        response.setToken(tokens.createToken(user.getId(), UserTypeEnum.USER));
        response.setUserId(user.getId());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setPasswordSetupRequired(false);
        response.setPhoneBound(true);
        return response;
    }
}
