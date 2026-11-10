package com.delta.common.maintenance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全局维护模式过滤器：
 * 当 MaintenanceState.enabled=false 时，除 /maintenance/** 外的所有请求直接返回 503。
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class MaintenanceFilter extends OncePerRequestFilter {

    // Payment providers must still reach the signature-verified callback endpoints
    // while the storefront is closed. The status/site endpoints let the maintenance
    // page render, and OPTIONS keeps CORS preflight working.
    private static final java.util.Set<String> ALWAYS_ALLOWED = java.util.Set.of(
            "/system/config/site", "/system/maintenance/status",
            "/pay/wx/notify", "/pay/wx/refund-notify", "/pay/wx/official/callback");

    private final MaintenanceState maintenanceState;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        // 维护页面本身始终允许访问
        boolean isMaintenancePath = uri.startsWith("/maintenance");
        boolean isProviderCallback = ALWAYS_ALLOWED.contains(uri)
                || uri.startsWith("/actuator/")
                || "OPTIONS".equals(request.getMethod());

        if (!maintenanceState.isEnabled() && !isMaintenancePath && !isProviderCallback) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":503,\"msg\":\"系统维护中，请稍后再试\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}

