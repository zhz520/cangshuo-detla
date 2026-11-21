package com.delta.marketing.service;

import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Customer coupon listing and claim rules. */
@Service
@RequiredArgsConstructor
public class CouponService {
    private final JdbcTemplate jdbc;
    private final SysConfigService configs;

    public List<Map<String, Object>> available() {
        if (!enabled()) return List.of();
        return jdbc.queryForList("SELECT id,name,type,coupon_value,min_spend,valid_days,per_user_limit," +
                "(total-claimed) remain FROM coupon_template WHERE status=1 AND (total=0 OR claimed<total) ORDER BY id DESC LIMIT 100");
    }

    public List<Map<String, Object>> mine(Long userId) {
        return jdbc.queryForList("SELECT uc.id,uc.status,uc.claimed_at,uc.expires_at,uc.used_at," +
                "ct.name,ct.type,ct.coupon_value,ct.min_spend FROM user_coupon uc JOIN coupon_template ct ON ct.id=uc.template_id " +
                "WHERE uc.user_id=? ORDER BY uc.claimed_at DESC LIMIT 200", userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void claim(Long templateId, Long userId) {
        if (!enabled()) throw new BusinessException(403, "优惠券发放已关闭");
        var rows = jdbc.queryForList("SELECT * FROM coupon_template WHERE id=? AND status=1 FOR UPDATE", templateId);
        if (rows.isEmpty()) throw new BusinessException(404, "优惠券不存在或已下架");
        var template = rows.get(0);
        int total = ((Number) template.get("total")).intValue();
        int claimed = ((Number) template.get("claimed")).intValue();
        if (total > 0 && claimed >= total) throw new BusinessException(409, "优惠券已领完");
        int limit = ((Number) template.get("per_user_limit")).intValue();
        Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE template_id=? AND user_id=?", Integer.class, templateId, userId);
        if (mine != null && mine >= limit) throw new BusinessException(409, "您已领取过该优惠券");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusDays(((Number) template.get("valid_days")).longValue());
        int updated = jdbc.update("UPDATE coupon_template SET claimed=claimed+1 WHERE id=? AND (total=0 OR claimed<total)", templateId);
        if (updated != 1) throw new BusinessException(409, "优惠券已领完");
        jdbc.update("INSERT INTO user_coupon(template_id,user_id,status,claimed_at,expires_at) VALUES(?,?,'AVAILABLE',?,?)",
                templateId, userId, now, expires);
    }

    private boolean enabled() {
        return "true".equalsIgnoreCase(configs.getConfigValue("marketing.coupon.issue_enabled", "false"));
    }
}
