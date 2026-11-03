package com.delta.common.security.controller;

import com.delta.common.domain.R;
import com.delta.common.security.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Revokes the presented bearer token on explicit logout. */
@RestController
@RequiredArgsConstructor
public class LogoutController {
    private final TokenService tokenService;

    @PostMapping("/auth/logout")
    public R<Void> logout(HttpServletRequest request) {
        tokenService.revoke(tokenService.getToken(request));
        return R.ok();
    }
}
