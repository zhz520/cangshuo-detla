package com.delta.notification.controller;

import com.delta.notification.service.WechatIdentityService;
import com.delta.notification.service.WechatWebLoginService;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/user/auth/wechat")
@RequiredArgsConstructor
public class WechatWebLoginController {
    private static final String OAUTH_COOKIE = "__Host-delta_wechat_oauth";
    private final WechatWebLoginService service;
    private final WechatIdentityService identity;

    public record Ticket(@NotBlank String ticket) {}
    public record SendCode(@NotBlank String ticket, @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone,
                           @NotBlank String captchaId, @NotBlank String captchaCode) {}
    public record BindPhone(@NotBlank String ticket, @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone,
                            @NotBlank String requestId, @NotBlank String code) {}

    @GetMapping("/start")
    public R<Map<String, String>> start(@RequestParam(required = false) String returnTo,
                                         @RequestParam(required = false) String invite, HttpServletResponse response) {
        return start(service.start(returnTo, invite, null), response);
    }

    @PostMapping("/bind/start")
    public R<Map<String, String>> startBinding(HttpServletResponse response) {
        return start(service.start(null, null, SecurityUtils.getUserId()), response);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                         @CookieValue(name = OAUTH_COOKIE, required = false) String browserState,
                         HttpServletResponse response) throws Exception {
        noStore(response);
        cookie(response, "", Duration.ZERO);
        String redirect;
        try {
            redirect = service.complete(code, state, browserState);
        } catch (Exception failure) {
            redirect = service.failureRedirect();
        }
        response.sendRedirect(redirect);
    }

    @PostMapping("/exchange")
    public R<WechatWebLoginService.Completion> exchange(@Valid @RequestBody Ticket body, HttpServletResponse response) {
        noStore(response);
        return R.ok(service.exchange(body.ticket()));
    }

    @PostMapping("/phone/code")
    public R<Map<String,Object>> sendCode(@Valid @RequestBody SendCode body, HttpServletRequest request,
                                           HttpServletResponse response, Authentication authentication) {
        noStore(response);
        return R.ok(service.sendCode(body.ticket(), body.phone(), body.captchaId(), body.captchaCode(),
                request.getRemoteAddr(), authenticatedUser(authentication)));
    }

    @PostMapping("/phone/complete")
    public R<WechatWebLoginService.Completion> complete(@Valid @RequestBody BindPhone body, HttpServletResponse response,
                                                          Authentication authentication) {
        noStore(response);
        return R.ok(service.bindPhone(body.ticket(), body.phone(), body.requestId(), body.code(), authenticatedUser(authentication)));
    }

    @PostMapping("/phone/confirm")
    public R<WechatWebLoginService.Completion> confirm(@Valid @RequestBody Ticket body, HttpServletResponse response,
                                                        Authentication authentication) {
        noStore(response);
        return R.ok(service.confirm(body.ticket(), authenticatedUser(authentication)));
    }

    private static Long authenticatedUser(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(role -> "ROLE_USER".equals(role.getAuthority()))
                ? SecurityUtils.getUserId() : null;
    }

    @GetMapping("/security")
    public R<WechatWebLoginService.SecurityStatus> security(HttpServletResponse response) {
        noStore(response);
        return R.ok(service.security(SecurityUtils.getUserId()));
    }

    @PostMapping("/bind/complete")
    public R<WechatWebLoginService.SecurityBinding> bindExisting(@Valid @RequestBody Ticket body, HttpServletResponse response) {
        noStore(response);
        return R.ok(service.bindExisting(SecurityUtils.getUserId(), body.ticket()));
    }

    @DeleteMapping("/bind")
    public R<Void> unbind() {
        identity.detach(SecurityUtils.getUserId());
        return R.ok();
    }

    private static R<Map<String, String>> start(WechatWebLoginService.Authorization result, HttpServletResponse response) {
        noStore(response);
        cookie(response, result.state(), Duration.ofMinutes(5));
        return R.ok(Map.of("authorizeUrl", result.url()));
    }

    private static void cookie(HttpServletResponse response, String value, Duration duration) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(OAUTH_COOKIE, value)
                .secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(duration).build().toString());
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
