package com.delta.admin.controller;

import com.delta.admin.service.WechatWebLoginService;
import com.delta.common.domain.R;
import com.delta.user.dto.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/user/auth/wechat")
@RequiredArgsConstructor
public class WechatWebLoginController {
    private final WechatWebLoginService service;

    @GetMapping("/start")
    public R<Map<String, String>> start(@RequestParam(required = false) String returnTo,
                                        HttpServletRequest request) {
        return R.ok(Map.of("authorizeUrl", service.startUrl(returnTo, request)));
    }

    @GetMapping("/callback")
    public void callback(@RequestParam String code, @RequestParam String state,
                         HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.sendRedirect(service.complete(code, state, request));
    }

    @PostMapping("/exchange")
    public R<LoginResponse> exchange(@RequestBody Map<String, String> body) {
        return R.ok(service.exchange(body.get("ticket")));
    }
}
