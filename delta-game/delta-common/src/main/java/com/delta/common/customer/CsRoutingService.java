package com.delta.common.customer;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客服归属解析。
 *
 * 规则优先级：商品 > 最近一级分类 > 上级分类 > 默认规则。
 * 规则目标可以是单个客服，也可以是客服组。客服组按当前上班状态选人，
 * 在线成员中优先选择进行中订单最少的客服；组内无人上班时不强行分配，
 * 订单进入公共池，由管理员处理或改派。
 */
@Service
@RequiredArgsConstructor
public class CsRoutingService {
    private final JdbcTemplate jdbc;

    private record RuleResult(boolean matched, Long csId) {}

    public Long resolveForProduct(Long productId) {
        if (productId == null) return resolveRule("DEFAULT", 0L).csId();
        RuleResult productRule = resolveRule("PRODUCT", productId);
        if (productRule.matched()) return productRule.csId();

        Long categoryId = firstLong("SELECT category_id FROM product WHERE id=? AND deleted=0", productId);
        Set<Long> visited = new HashSet<>();
        int depth = 0;
        while (categoryId != null && categoryId > 0 && depth++ < 20 && visited.add(categoryId)) {
            RuleResult categoryRule = resolveRule("CATEGORY", categoryId);
            if (categoryRule.matched()) return categoryRule.csId();
            categoryId = firstLong("SELECT parent_id FROM category WHERE id=? AND deleted=0", categoryId);
        }
        return resolveRule("DEFAULT", 0L).csId();
    }

    public Long resolveForOrder(Long orderId) {
        if (orderId == null) return null;
        Long assigned = orderCsId(orderId);
        if (assigned != null && isActiveCs(assigned)) return assigned;
        Long productId = firstLong("SELECT product_id FROM `order` WHERE id=?", orderId);
        return resolveForProduct(productId);
    }

    public Long anyOnDutyCs() {
        List<Long> rows = jdbc.queryForList(
                "SELECT a.id FROM admin a JOIN staff_shift s ON s.admin_id=a.id " +
                        "WHERE a.status=1 AND a.deleted=0 AND UPPER(a.role) IN ('CS','BOTH') AND s.online=1 " +
                        "ORDER BY a.id LIMIT 1", Long.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long orderCsId(Long orderId) {
        if (orderId == null) return null;
        return firstLong("SELECT cs_id FROM `order` WHERE id=?", orderId);
    }

    public boolean isActiveCs(Long csId) {
        if (csId == null) return false;
        Long count = firstLong("SELECT COUNT(*) FROM admin WHERE id=? AND UPPER(role) IN ('CS','BOTH') AND status=1 AND deleted=0", csId);
        return count != null && count > 0;
    }

    public boolean canCsHandleOrder(Long orderId, Long csId) {
        if (orderId == null || csId == null) return false;
        List<Long> rows = jdbc.queryForList("SELECT cs_id FROM `order` WHERE id=?", Long.class, orderId);
        if (rows.isEmpty()) return false;
        Long owner = rows.get(0);
        return owner == null || owner.equals(csId);
    }

    public void assertCsCanHandleOrder(Long orderId, Long csId) {
        if (orderId == null) throw new BusinessException(404, "订单不存在");
        List<Long> rows = jdbc.queryForList("SELECT cs_id FROM `order` WHERE id=?", Long.class, orderId);
        if (rows.isEmpty()) throw new BusinessException(404, "订单不存在");
        Long owner = rows.get(0);
        if (owner != null && !owner.equals(csId)) {
            throw new BusinessException(403, "该订单归属其他客服，无法查看或处理");
        }
    }

    private RuleResult resolveRule(String matchType, Long targetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT assignee_type,assignee_id FROM cs_routing_rule " +
                        "WHERE enabled=1 AND match_type=? AND target_id=? ORDER BY id DESC LIMIT 1",
                matchType, targetId);
        if (rows.isEmpty()) return new RuleResult(false, null);
        Map<String, Object> rule = rows.get(0);
        String type = String.valueOf(rule.get("assignee_type"));
        Number id = (Number) rule.get("assignee_id");
        if (id == null) return new RuleResult(true, null);
        if ("CS".equalsIgnoreCase(type)) {
            // A disabled/deleted CS is an invalid rule; let the next category/default rule win.
            return isActiveCs(id.longValue()) ? new RuleResult(true, id.longValue()) : new RuleResult(false, null);
        }
        if ("GROUP".equalsIgnoreCase(type)) {
            return new RuleResult(true, pickOnDutyMember(id.longValue()));
        }
        return new RuleResult(true, null);
    }

    private Long pickOnDutyMember(Long groupId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.id, COUNT(o.id) AS active_orders " +
                        "FROM admin a JOIN cs_group_member m ON m.admin_id=a.id " +
                        "JOIN cs_group g ON g.id=m.group_id AND g.enabled=1 " +
                        "LEFT JOIN staff_shift s ON s.admin_id=a.id " +
                        "LEFT JOIN `order` o ON o.cs_id=a.id AND o.status NOT IN ('CONFIRMED','CANCELLED','REFUNDED','ARBITRATED') " +
                        "WHERE m.group_id=? AND a.status=1 AND a.deleted=0 " +
                        "AND UPPER(a.role) IN ('CS','BOTH') AND COALESCE(s.online,0)=1 " +
                        "GROUP BY a.id ORDER BY active_orders ASC, a.id ASC LIMIT 1",
                groupId);
        if (rows.isEmpty()) return null;
        Number id = (Number) rows.get(0).get("id");
        return id == null ? null : id.longValue();
    }

    private Long firstLong(String sql, Object... args) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
