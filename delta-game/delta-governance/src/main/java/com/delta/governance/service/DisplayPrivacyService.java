package com.delta.governance.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** User-controlled visibility for the public display surfaces. */
@Service
@RequiredArgsConstructor
public class DisplayPrivacyService {
    private final JdbcTemplate jdbc;

    public Map<String, Object> current(Long userId) {
        var rows = jdbc.queryForList(
                "SELECT spend_rank_visible,purchase_feed_consent,updated_at FROM display_privacy WHERE user_id=?", userId);
        if (rows.isEmpty()) return Map.of("spendRankVisible", true, "purchaseFeedConsent", false);
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spendRankVisible", number(row.get("spend_rank_visible")) == 1);
        result.put("purchaseFeedConsent", number(row.get("purchase_feed_consent")) == 1);
        result.put("updatedAt", row.get("updated_at"));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Long userId, Map<String, Object> body) {
        Boolean rankVisible = bool(body.get("spendRankVisible"), "消费排行展示设置无效");
        Boolean feedConsent = bool(body.get("purchaseFeedConsent"), "最近购买展示设置无效");
        if (rankVisible == null && feedConsent == null) throw new BusinessException(400, "没有需要保存的展示设置");
        Map<String, Object> existing = current(userId);
        boolean nextRank = rankVisible == null ? Boolean.TRUE.equals(existing.get("spendRankVisible")) : rankVisible;
        boolean nextFeed = feedConsent == null ? Boolean.TRUE.equals(existing.get("purchaseFeedConsent")) : feedConsent;
        int updated = jdbc.update("UPDATE display_privacy SET spend_rank_visible=?,purchase_feed_consent=? WHERE user_id=?",
                nextRank ? 1 : 0, nextFeed ? 1 : 0, userId);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO display_privacy(user_id,spend_rank_visible,purchase_feed_consent) VALUES(?,?,?)",
                        userId, nextRank ? 1 : 0, nextFeed ? 1 : 0);
            } catch (DuplicateKeyException race) {
                jdbc.update("UPDATE display_privacy SET spend_rank_visible=?,purchase_feed_consent=? WHERE user_id=?",
                        nextRank ? 1 : 0, nextFeed ? 1 : 0, userId);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spendRankVisible", nextRank);
        result.put("purchaseFeedConsent", nextFeed);
        return result;
    }

    private static Boolean bool(Object value, String message) {
        if (value == null) return null;
        if (!(value instanceof Boolean)) throw new BusinessException(400, message);
        return (Boolean) value;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
