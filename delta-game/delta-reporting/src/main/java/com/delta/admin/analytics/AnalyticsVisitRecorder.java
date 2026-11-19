package com.delta.admin.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.delta.common.tenant.TenantContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/** Persists one throttled, privacy-preserving anonymous visit row per visitor and day. */
@Service
public class AnalyticsVisitRecorder {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long THROTTLE_MILLIS = 5 * 60 * 1000L;
    private static final int CACHE_LIMIT = 50_000;
    private static final String UPSERT =
            "INSERT INTO analytics_daily_visitor(visit_date,visitor_key,device_hash,user_type,user_id,hits) " +
            "VALUES(?,?,?,?,?,1) ON DUPLICATE KEY UPDATE " +
            "device_hash=VALUES(device_hash),user_type=COALESCE(VALUES(user_type),user_type)," +
            "user_id=COALESCE(VALUES(user_id),user_id),last_seen_at=CURRENT_TIMESTAMP,hits=hits+1";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String secret;
    private final ConcurrentHashMap<String, Long> recentWrites = new ConcurrentHashMap<>();

    AnalyticsVisitRecorder(JdbcTemplate jdbc, Clock clock, String secret) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.secret = secret;
    }

    @Autowired
    public AnalyticsVisitRecorder(JdbcTemplate jdbc, @Value("${jwt.secret}") String secret) {
        this(jdbc, Clock.system(BUSINESS_ZONE), secret);
    }

    public void record(HttpServletRequest request, String userType, Long userId) {
        LocalDate today = LocalDate.now(clock);
        String userAgent = request.getHeader("User-Agent");
        String deviceHash = digest("device\n" + request.getRemoteAddr() + "\n" + (userAgent == null ? "" : userAgent));
        String visitorHash = digest("visitor-device\n" + deviceHash);
        String normalizedUserType = userId == null ? null : userType;
        String tenant=TenantContext.currentIdOrNull();
        String throttleKey = (tenant==null?"default":tenant) + "|" + today + "|" + visitorHash + "|" + normalizedUserType + "|" + userId;
        long now = clock.millis();
        Long previous = recentWrites.putIfAbsent(throttleKey, now);
        if (previous != null && now - previous < THROTTLE_MILLIS) return;
        if (previous != null) recentWrites.replace(throttleKey, previous, now);
        if (recentWrites.size() > CACHE_LIMIT) recentWrites.clear();
        try {
            jdbc.update(UPSERT, today, visitorHash, deviceHash, normalizedUserType, userId);
        } catch (RuntimeException ignored) {
            recentWrites.remove(throttleKey, now);
        }
    }

    String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("访客标识摘要失败", error);
        }
    }

}
