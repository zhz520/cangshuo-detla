package com.delta.common.security.filter;

import com.delta.common.security.service.TokenService;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final TokenService tokenService;
    @Autowired(required = false)
    private TenantProperties tenantProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = tokenService.getToken(request);
        if (token != null) {
            Claims claims = tokenService.parseToken(token);
            if (claims != null) {
                String requestTenant = TenantContext.currentIdOrNull();
                String tokenTenant = claims.get("tenantId", String.class);
                if (tenantProperties != null && tenantProperties.isEnabled()
                        && (requestTenant == null || !requestTenant.equals(tokenTenant))) {
                    chain.doFilter(request, response);
                    return;
                }
                String uuid = claims.get("uuid", String.class);
                Map<String, Object> userContext = tokenService.getUserContext(uuid);
                if (userContext != null && String.valueOf(userContext.get("userId")).equals(String.valueOf(claims.get("userId")))
                        && userContext.get("userType").equals(claims.get("userType"))
                        && (!(tenantProperties != null && tenantProperties.isEnabled())
                            || requestTenant.equals(userContext.get("tenantId")))) {
                    // 根据 userType 构建角色权限
                    List<org.springframework.security.core.GrantedAuthority> authorities = buildAuthorities(
                            (String) userContext.get("userType"));
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            userContext, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }

    private List<org.springframework.security.core.GrantedAuthority> buildAuthorities(String userType) {
        if (userType == null) return Collections.emptyList();
        return switch (userType.toUpperCase()) {
            case "ADMIN" -> List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
            case "CS" -> List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CS"));
            case "USER" -> List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
            case "GUEST" -> List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_GUEST"));
            case "PLAYER" -> List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PLAYER"));
            default -> Collections.emptyList();
        };
    }
}
