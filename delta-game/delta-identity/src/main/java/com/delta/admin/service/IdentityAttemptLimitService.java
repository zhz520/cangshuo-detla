package com.delta.admin.service;

import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Per-account failure limit for automatic real-name checks. */
@Service
@RequiredArgsConstructor
public class IdentityAttemptLimitService {
    private static final String ENABLED = "identity.auto.attempt_limit_enabled";
    private static final String MAX_ATTEMPTS = "identity.auto.max_failed_attempts";
    private static final String WINDOW_HOURS = "identity.auto.failed_window_hours";
    private static final String LOCK_HOURS = "identity.auto.lock_hours";
    private static final DateTimeFormatter LOCK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final SysConfigService configs;

    public record Settings(boolean enabled, int maxAttempts, int windowHours, int lockHours) {}
    public record State(int failedAttempts, LocalDateTime lockedUntil, int maxAttempts, int remainingAttempts,
                        boolean locked) {}
    private record Row(int failedAttempts, LocalDateTime lastFailedAt, LocalDateTime lockedUntil) {}

    public Settings settings() {
        return new Settings(
                "true".equalsIgnoreCase(configs.getConfigValue(ENABLED, "true")),
                integer(MAX_ATTEMPTS, 5, 1, 20),
                integer(WINDOW_HOURS, 24, 1, 720),
                integer(LOCK_HOURS, 24, 1, 720));
    }

    @Transactional
    public Settings save(boolean enabled, int maxAttempts, int windowHours, int lockHours) {
        if (maxAttempts < 1 || maxAttempts > 20) throw new BusinessException(400, "最大失败次数须为 1 至 20");
        if (windowHours < 1 || windowHours > 720) throw new BusinessException(400, "统计窗口须为 1 至 720 小时");
        if (lockHours < 1 || lockHours > 720) throw new BusinessException(400, "锁定时长须为 1 至 720 小时");
        write(ENABLED, "启用实名失败次数限制", String.valueOf(enabled));
        write(MAX_ATTEMPTS, "同一账号最大实名失败次数", String.valueOf(maxAttempts));
        write(WINDOW_HOURS, "实名失败次数统计窗口（小时）", String.valueOf(windowHours));
        write(LOCK_HOURS, "实名失败锁定小时数", String.valueOf(lockHours));
        return settings();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureAllowed(long userId) {
        Settings settings = settings();
        if (!settings.enabled()) return;
        ensureRow(userId);
        LocalDateTime now = LocalDateTime.now();
        Row row = row(userId);
        if (row.lockedUntil() != null && row.lockedUntil().isAfter(now)) {
            throw new BusinessException(429, "实名认证失败次数过多，请于 " + row.lockedUntil().format(LOCK_TIME) + " 后重试");
        }
        if (row.lockedUntil() != null) {
            resetRow(userId);
            row = new Row(0, null, null);
        }
        if (row.lastFailedAt() != null && row.lastFailedAt().isBefore(now.minusHours(settings.windowHours()))) {
            resetRow(userId);
            row = new Row(0, null, null);
        }
        if (row.failedAttempts() >= settings.maxAttempts()) {
            LocalDateTime lockedUntil = now.plusHours(settings.lockHours());
            lockRow(userId, lockedUntil);
            throw new BusinessException(429, "实名认证失败次数过多，请于 " + lockedUntil.format(LOCK_TIME) + " 后重试");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public State recordFailure(long userId) {
        Settings settings = settings();
        if (!settings.enabled()) return new State(0, null, settings.maxAttempts(), settings.maxAttempts(), false);
        ensureRow(userId);
        LocalDateTime now = LocalDateTime.now();
        Row row = row(userId);
        if (row.lockedUntil() != null && !row.lockedUntil().isAfter(now)) {
            resetRow(userId);
            row = new Row(0, null, null);
        }
        if (row.lastFailedAt() != null && row.lastFailedAt().isBefore(now.minusHours(settings.windowHours()))) {
            resetRow(userId);
            row = new Row(0, null, null);
        }
        int failed = row.failedAttempts() + 1;
        LocalDateTime lockedUntil = failed >= settings.maxAttempts() ? now.plusHours(settings.lockHours()) : null;
        jdbc.update("UPDATE identity_verification SET failed_attempts=?,last_failed_at=?,locked_until=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                failed, Timestamp.valueOf(now), lockedUntil == null ? null : Timestamp.valueOf(lockedUntil), userId);
        return new State(failed, lockedUntil, settings.maxAttempts(), Math.max(0, settings.maxAttempts() - failed), lockedUntil != null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(long userId) {
        jdbc.update("UPDATE identity_verification SET failed_attempts=0,last_failed_at=NULL,locked_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", userId);
    }

    private void ensureRow(long userId) {
        jdbc.update("INSERT INTO identity_verification(user_id,status) VALUES(?,'DRAFT') ON DUPLICATE KEY UPDATE status=status", userId);
    }

    private Row row(long userId) {
        var rows = jdbc.queryForList("SELECT failed_attempts,last_failed_at,locked_until FROM identity_verification WHERE user_id=?", userId);
        if (rows.isEmpty()) return new Row(0, null, null);
        Map<String, Object> row = rows.get(0);
        return new Row(
                row.get("failed_attempts") == null ? 0 : ((Number) row.get("failed_attempts")).intValue(),
                local(row.get("last_failed_at")),
                local(row.get("locked_until")));
    }

    private void resetRow(long userId) {
        jdbc.update("UPDATE identity_verification SET failed_attempts=0,last_failed_at=NULL,locked_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", userId);
    }

    private void lockRow(long userId, LocalDateTime lockedUntil) {
        jdbc.update("UPDATE identity_verification SET locked_until=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", Timestamp.valueOf(lockedUntil), userId);
    }

    private int integer(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(configs.getConfigValue(key, String.valueOf(fallback)));
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void write(String key, String name, String value) {
        jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group) " +
                "VALUES(?,?,?,'number','实名配置') ON DUPLICATE KEY UPDATE config_value=?",
                key, name, value, value);
        configs.evictCache(key);
    }

    private static LocalDateTime local(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof LocalDateTime dateTime) return dateTime;
        return null;
    }
}
