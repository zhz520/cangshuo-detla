package com.delta.cs.controller;

import com.delta.common.domain.R;
import com.delta.cs.dto.AdminLoginRequest;
import com.delta.cs.dto.AdminLoginResponse;
import com.delta.cs.service.AdminAuthService;
import com.delta.common.security.service.VerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cs/auth")
@RequiredArgsConstructor
public class CsAuthController {
    private final AdminAuthService adminAuthService;
    private final VerificationService verification;

    @GetMapping("/captcha")
    public R<Map<String,String>> captcha(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return R.ok(verification.captcha(request.getRemoteAddr()));
    }

    @PostMapping("/login")
    public R<AdminLoginResponse> login(@RequestBody AdminLoginRequest request) {
        return R.ok(adminAuthService.login(request));
    }
}
