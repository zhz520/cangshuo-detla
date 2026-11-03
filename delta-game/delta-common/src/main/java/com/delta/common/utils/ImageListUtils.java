package com.delta.common.utils;

import com.alibaba.fastjson2.JSON;
import com.delta.common.exception.BusinessException;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ImageListUtils {
    private ImageListUtils() {}

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            try {
                return join(JSON.parseArray(value, String.class));
            } catch (Exception ignored) {
                // Fall back to comma-separated parsing below.
            }
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return join(Arrays.asList(value.split(",")));
    }

    /** Normalize user-submitted media and reject executable, relative or unbounded URLs. */
    public static String normalizeSafe(String raw, int maxItems) {
        String normalized = normalize(raw);
        if (normalized == null) return null;
        String[] items = normalized.split(",");
        if (items.length > maxItems) throw new BusinessException(400, "图片数量超过限制");
        for (String item : items) {
            String value = item.trim();
            if (value.length() > 512) throw new BusinessException(400, "图片地址过长");
            if (value.startsWith("/file/")) {
                if (!value.matches("/file/[A-Za-z0-9_./-]+") || value.contains("..") || value.startsWith("/file//"))
                    throw new BusinessException(400, "图片地址无效");
                continue;
            }
            try {
                URI uri = URI.create(value);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                throw new BusinessException(400, "图片地址无效");
            }
        }
        return normalized;
    }

    public static String normalizeLocal(String raw, int maxItems) {
        String normalized = normalize(raw);
        if (normalized == null) return null;
        String[] items = normalized.split(",");
        if (items.length > maxItems) throw new BusinessException(400, "图片数量超过限制");
        for (String item : items) {
            String value = item.trim();
            if (value.length() > 512 || !value.matches("/file/[A-Za-z0-9_./-]+")
                    || value.contains("..") || value.startsWith("/file//"))
                throw new BusinessException(400, "图片地址无效");
        }
        return normalized;
    }

    private static String join(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String image : images) {
            if (image == null) {
                continue;
            }
            String value = image.trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }
}
