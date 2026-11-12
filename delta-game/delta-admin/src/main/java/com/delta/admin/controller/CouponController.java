package com.delta.admin.controller;

import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user/coupons")
@RequiredArgsConstructor
public class CouponController {
    private final JdbcTemplate jdbc;
    private final SysConfigService configs;

    @GetMapping("/available")
    public R<List<Map<String, Object>>> available() {
        if (!enabled()) return R.ok(List.of());
        return R.ok(jdbc.queryForList("SELECT id,name,type,value,min_spend,valid_days,per_user_limit," +
                "(total-claimed) remain FROM coupon_template WHERE status=1 AND (total=0 OR claimed<total) ORDER BY id DESC LIMIT 100"));
    }

    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine() {
        return R.ok(jdbc.queryForList("SELECT uc.id,uc.status,uc.claimed_at,uc.expires_at,uc.used_at," +
                "ct.name,ct.type,ct.value,ct.min_spend FROM user_coupon uc JOIN coupon_template ct ON ct.id=uc.template_id " +
                "WHERE uc.user_id=? ORDER BY uc.claimed_at DESC LIMIT 200", SecurityUtils.getUserId()));
    }

    @PostMapping("/{templateId}/claim")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> claim(@PathVariable Long templateId) {
        if (!enabled()) throw new BusinessException(403, "优惠券发放已关闭");
        Long userId = SecurityUtils.getUserId();
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
        return R.ok();
    }

    private boolean enabled() {
        return "true".equalsIgnoreCase(configs.getConfigValue("marketing.coupon.issue_enabled", "false"));
    }
}
