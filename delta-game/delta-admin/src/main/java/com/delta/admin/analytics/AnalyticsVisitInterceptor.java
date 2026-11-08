package com.delta.admin.analytics;

import com.delta.common.security.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/** Samples H5 traffic after authentication so DAU can be derived without client changes. */
@Component
@RequiredArgsConstructor
public class AnalyticsVisitInterceptor implements HandlerInterceptor {
    private static final Set<String> H5_PREFIXES = Set.of(
            "/app", "/product", "/order", "/user", "/player", "/pay", "/guest", "/system", "/common/chat");
    private static final Set<String> PAYMENT_CALLBACKS = Set.of(
            "/pay/wx/notify", "/pay/wx/refund-notify", "/pay/wx/official/callback");

    private final AnalyticsVisitRecorder recorder;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = normalizedPath(request);
        if (!isH5Path(path) || PAYMENT_CALLBACKS.contains(path)) return true;
        String userType = null;
        Long userId = null;
        try {
            userType = SecurityUtils.getUserType();
            userId = SecurityUtils.getUserId();
        } catch (RuntimeException ignored) {
            // Public H5 traffic is intentionally anonymous.
        }
        try {
            recorder.record(request, userType, userId);
        } catch (RuntimeException ignored) {
            // Analytics must never affect a customer request.
        }
        return true;
    }

    private String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isBlank() && path.startsWith(context)) path = path.substring(context.length());
        if (path.startsWith("/api/")) path = path.substring(4);
        return path;
    }

    private boolean isH5Path(String path) {
        for (String prefix : H5_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }
}
