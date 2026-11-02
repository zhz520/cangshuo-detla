package com.delta.admin.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Component @Order(-200) @RequiredArgsConstructor
public class ProjectMaintenanceFilter extends OncePerRequestFilter {
    private final PlatformRemote remote;
    private final ObjectMapper json;
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String route=request.getServletPath();
        // Recovery and settlement callbacks remain reachable; authorization still protects admin routes.
        boolean recovery=route.startsWith("/admin/platform/")||route.startsWith("/user/auth/")||route.equals("/cs/auth/login")||route.equals("/cs/permissions")
                ||route.equals("/user/privacy/requests")||route.startsWith("/admin/privacy/requests")
                ||route.equals("/system/content/key/privacy_policy")||route.equals("/system/content/key/user_agreement")
                ||route.equals("/system/config/site")||route.equals("/system/maintenance/status")||route.startsWith("/actuator/")||route.equals("/pay/wx/notify")||route.equals("/pay/wx/refund-notify")||"OPTIONS".equals(request.getMethod());
        boolean finishExisting="FINISH_EXISTING".equals(remote.project().path("pausePolicy").asText()) && isExistingSettlement(route,request.getMethod());
        if(!recovery && (remote.updating() || (!finishExisting && remote.suspended()))) {
            response.setStatus(503);response.setCharacterEncoding("UTF-8");response.setContentType("application/json");response.setHeader("Retry-After","3600");
            json.writeValue(response.getWriter(),Map.of("code",503,"msg","项目维护期已到期，业务暂时停止，请联系管理员续期","maintenance",true));return;
        }
        String forwarded=request.getHeader("X-Forwarded-Host"),domain=forwarded==null?request.getServerName():forwarded.split(",",2)[0].trim();
        if(!recovery&&!remote.domainAllowed(domain)) {
            response.setStatus(403);response.setCharacterEncoding("UTF-8");response.setContentType("application/json");
            json.writeValue(response.getWriter(),Map.of("code",403,"msg","当前访问域名未授权，请使用项目登记的域名"));return;
        }
        chain.doFilter(request,response);
    }
    static boolean isExistingSettlement(String route,String method) {
        // Explicitly restricted to existing order detail/settlement; creation and payment initiation stay blocked.
        return ("GET".equals(method)&&route.matches("/(order|player/work|cs/order)/[0-9]+"))
                || ("POST".equals(method)&&route.matches("/(order|player/work|cs/order)/[0-9]+/(complete|confirm|cancel)"));
    }
}
