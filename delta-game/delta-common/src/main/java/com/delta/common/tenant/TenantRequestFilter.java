package com.delta.common.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/** Resolves the tenant from the host resolved by the servlet container before any auth work. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
@RequiredArgsConstructor
public class TenantRequestFilter extends OncePerRequestFilter {
    private final TenantRegistry registry;
    private final ObjectMapper json;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!registry.isSharedEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        TenantDefinition tenant = registry.findByHost(request.getServerName()).orElse(null);
        if (tenant == null) {
            reject(response, 404, "当前访问域名未登记");
            return;
        }
        if (!tenant.active()) {
            reject(response, 503, "当前租户暂不可用，请联系维护人员");
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.open(tenant.id())) {
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        json.writeValue(response.getWriter(), Map.of("code", status, "msg", message));
    }
}
