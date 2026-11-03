package com.delta.common.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

public class IpUtils {
    public static String getIpAddr(HttpServletRequest request) {
        // Tomcat's RemoteIpValve resolves forwarded headers only from trusted proxies.
        String ip = request.getRemoteAddr();
        if (!StringUtils.hasText(ip)) ip = "unknown";
        return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
    }
}
