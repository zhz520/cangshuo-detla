package com.delta.admin.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/coupons")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
@RequiredArgsConstructor
public class CouponAdminController {
    private final JdbcTemplate jdbc;

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(jdbc.queryForList("SELECT * FROM coupon_template ORDER BY id DESC LIMIT 500"));
    }

    @GetMapping("/records")
    public R<List<Map<String, Object>>> records() {
        return R.ok(jdbc.queryForList(
                "SELECT uc.id,uc.status,uc.claimed_at,uc.expires_at,uc.used_order_id," +
                        "u.nickname,u.phone,ct.name coupon_name,ct.type,ct.coupon_value,ct.min_spend " +
                        "FROM user_coupon uc JOIN `user` u ON u.id=uc.user_id " +
                        "JOIN coupon_template ct ON ct.id=uc.template_id " +
                        "ORDER BY uc.id DESC LIMIT 500"));
    }

    @PostMapping
    @OpLog(module = "marketing", operation = "创建优惠券模板")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> create(@RequestBody Map<String, Object> body) {
        Template t = template(body);
        jdbc.update("INSERT INTO coupon_template(name,type,coupon_value,min_spend,valid_days,total,per_user_limit,status) VALUES(?,?,?,?,?,?,?,?)",
                t.name(), t.type(), t.value(), t.minSpend(), t.validDays(), t.total(), t.perUserLimit(), t.status());
        return R.ok();
    }

    @PutMapping("/{id}")
    @OpLog(module = "marketing", operation = "修改优惠券模板")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        var current = jdbc.queryForList("SELECT claimed,type,coupon_value,min_spend FROM coupon_template WHERE id=? FOR UPDATE", id);
        if (current.isEmpty())
            throw new BusinessException(404, "优惠券模板不存在");
        var existing = current.get(0);
        Template t = template(body);
        int claimed = ((Number) existing.get("claimed")).intValue();
        if (claimed > 0) {
            BigDecimal currentValue = (BigDecimal) existing.get("coupon_value");
            BigDecimal currentMinSpend = (BigDecimal) existing.get("min_spend");
            boolean termsChanged = !t.type().equals(String.valueOf(existing.get("type")))
                    || t.value().compareTo(currentValue) != 0
                    || t.minSpend().compareTo(currentMinSpend) != 0;
            if (termsChanged)
                throw new BusinessException(409, "已有用户领取，不能修改优惠券类型、面额或使用门槛；请停用后新建模板");
        }
        if (t.total() > 0 && t.total() < claimed)
            throw new BusinessException(400, "发放总量不能小于已领取数量");
        jdbc.update("UPDATE coupon_template SET name=?,type=?,coupon_value=?,min_spend=?,valid_days=?,total=?,per_user_limit=?,status=? WHERE id=?",
                t.name(), t.type(), t.value(), t.minSpend(), t.validDays(), t.total(), t.perUserLimit(), t.status(), id);
        return R.ok();
    }

    private Template template(Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String type = String.valueOf(body.getOrDefault("type", "AMOUNT")).trim().toUpperCase();
        if (name.isEmpty() || name.length() > 80) throw new BusinessException(400, "优惠券名称无效");
        if (!List.of("AMOUNT", "DISCOUNT").contains(type)) throw new BusinessException(400, "优惠券类型无效");
        BigDecimal value = decimal(body.get("value"));
        BigDecimal minSpend = decimal(body.get("minSpend"));
        int validDays = integer(body.get("validDays"), 1, 3650);
        int total = integer(body.get("total"), 0, 1000000);
        int perUser = integer(body.get("perUserLimit"), 1, 100);
        int status = body.get("status") == null ? 1 : integer(body.get("status"), 0, 1);
        if (value.signum() <= 0) throw new BusinessException(400, "优惠券面额必须大于0");
        if ("DISCOUNT".equals(type) && value.compareTo(BigDecimal.ONE) >= 0)
            throw new BusinessException(400, "折扣券面额必须小于1");
        if (minSpend.signum() < 0) throw new BusinessException(400, "最低消费不能为负数");
        return new Template(name, type, value, minSpend, validDays, total, perUser, status);
    }

    private BigDecimal decimal(Object value) {
        try { return new BigDecimal(String.valueOf(value)).setScale(2, java.math.RoundingMode.UNNECESSARY); }
        catch (Exception error) { throw new BusinessException(400, "优惠券金额格式不正确"); }
    }

    private int integer(Object value, int min, int max) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (Exception error) { throw new BusinessException(400, "优惠券参数超出范围"); }
    }

    public record Template(String name, String type, BigDecimal value, BigDecimal minSpend,
                           int validDays, int total, int perUserLimit, int status) {}
}
