package com.delta.order.service;

import com.delta.common.exception.BusinessException;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantScheduler;
import com.delta.common.utils.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * First flash-sale version: one fixed price, independent stock, one claim per
 * account and no coupon/member/player price stacking.
 */
@Service
@RequiredArgsConstructor
public class FlashSaleService {
    private final JdbcTemplate jdbc;
    @Autowired(required = false) private TenantRegistry tenantRegistry;

    public record Reservation(long activityId, BigDecimal price) {}

    public List<Map<String, Object>> list() {
        return jdbc.query("SELECT f.*,p.name AS product_name FROM flash_sale_activity f LEFT JOIN product p ON p.id=f.product_id " +
                        "ORDER BY f.created_at DESC,f.id DESC",
                (rs, n) -> row(rs));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long id, Map<String, Object> body) {
        Long productId = positiveLong(body.get("productId"), "请选择秒杀商品");
        String name = required(body.get("name"), "请填写活动名称", 80);
        BigDecimal price = decimal(body.get("price"), "秒杀价格必须大于 0");
        int stock = integer(body.get("stock"), 1, 1_000_000, "秒杀库存必须为 1 至 1000000");
        LocalDateTime startsAt = time(body.get("startsAt"), "请填写开始时间");
        LocalDateTime endsAt = time(body.get("endsAt"), "请填写结束时间");
        if (!startsAt.isBefore(endsAt)) throw new BusinessException(400, "结束时间必须晚于开始时间");
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        Integer product = jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=? AND status=1 AND deleted=0", Integer.class, productId);
        if (product == null || product == 0) throw new BusinessException(400, "秒杀商品不存在或已下架");
        if (enabled) {
            Integer overlap = jdbc.queryForObject("SELECT COUNT(*) FROM flash_sale_activity WHERE product_id=? AND status=1 AND id<>? " +
                            "AND starts_at<? AND ends_at>?", Integer.class, productId, id == null ? 0 : id, endsAt, startsAt);
            if (overlap != null && overlap > 0) throw new BusinessException(409, "该商品已有时间重叠的秒杀活动");
        }
        if (id == null) {
            jdbc.update("INSERT INTO flash_sale_activity(name,product_id,price,total_stock,remaining_stock,starts_at,ends_at,status) " +
                            "VALUES(?,?,?,?,?,?,?,?)", name, productId, price, stock, stock, startsAt, endsAt, enabled ? 1 : 0);
            id = jdbc.queryForObject("SELECT id FROM flash_sale_activity WHERE name=? AND product_id=? ORDER BY id DESC LIMIT 1",
                    Long.class, name, productId);
        } else {
            int changed = jdbc.update("UPDATE flash_sale_activity SET name=?,product_id=?,price=?,total_stock=?,remaining_stock=remaining_stock+(?-total_stock)," +
                            "starts_at=?,ends_at=?,status=? WHERE id=?", name, productId, price, stock, stock, startsAt, endsAt, enabled ? 1 : 0, id);
            if (changed == 0) throw new BusinessException(404, "秒杀活动不存在");
        }
        Long savedId = id;
        List<Map<String, Object>> saved = jdbc.query(
                "SELECT f.*,p.name AS product_name FROM flash_sale_activity f LEFT JOIN product p ON p.id=f.product_id WHERE f.id=?",
                (rs, n) -> row(rs), savedId);
        if (saved.isEmpty()) throw new BusinessException(500, "秒杀活动保存后无法回读");
        return saved.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Integer claims = jdbc.queryForObject("SELECT COUNT(*) FROM flash_sale_claim WHERE activity_id=?", Integer.class, id);
        if (claims != null && claims > 0) throw new BusinessException(409, "已有用户参与过该活动，不能删除，可停用");
        if (jdbc.update("DELETE FROM flash_sale_activity WHERE id=?", id) == 0) throw new BusinessException(404, "秒杀活动不存在");
    }

    public Map<String, Object> activeForProduct(Long productId, Long userId) {
        Map<String, Object> activity = current(productId, LocalDateTime.now(), false);
        if (activity == null) return Map.of("active", false, "productId", productId);
        Map<String, Object> result = new LinkedHashMap<>(activity);
        result.remove("status");
        if (userId != null) {
            Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM flash_sale_claim WHERE activity_id=? AND user_id=?", Integer.class,
                    activity.get("id"), userId);
            result.put("claimed", used != null && used > 0);
        }
        return result;
    }

    /** Returns the active flash price for quoting, rejecting a second claim. */
    public BigDecimal activePrice(Long productId, Long userId) {
        Map<String, Object> activity = current(productId, LocalDateTime.now(), false);
        if (activity == null) return null;
        if (userId != null) {
            Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM flash_sale_claim WHERE activity_id=? AND user_id=?", Integer.class,
                    activity.get("id"), userId);
            if (used != null && used > 0) throw new BusinessException(409, "该账号已参加过本次秒杀");
        }
        return (BigDecimal) activity.get("price");
    }

    @Transactional(rollbackFor = Exception.class)
    public Reservation reserveIfActive(Long userId, Long productId) {
        Map<String, Object> activity = current(productId, LocalDateTime.now(), true);
        if (activity == null) return null;
        Long activityId = (Long) activity.get("id");
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM flash_sale_claim WHERE activity_id=? AND user_id=?", Integer.class, activityId, userId);
        if (used != null && used > 0) throw new BusinessException(409, "该账号已参加过本次秒杀");
        int stock = jdbc.update("UPDATE flash_sale_activity SET remaining_stock=remaining_stock-1 WHERE id=? AND remaining_stock>0", activityId);
        if (stock == 0) throw new BusinessException(409, "秒杀名额已抢完");
        BigDecimal price = (BigDecimal) activity.get("price");
        try {
            jdbc.update("INSERT INTO flash_sale_claim(activity_id,user_id,price,status) VALUES(?,?,?,'RESERVED')", activityId, userId, price);
        } catch (org.springframework.dao.DuplicateKeyException error) {
            throw new BusinessException(409, "该账号已参加过本次秒杀");
        }
        return new Reservation(activityId, price);
    }

    public void attachOrder(Reservation reservation, Long orderId) {
        if (reservation == null) return;
        int changed = jdbc.update("UPDATE flash_sale_claim SET order_id=?,status='ORDERED' WHERE activity_id=? AND status='RESERVED' AND order_id IS NULL",
                orderId, reservation.activityId());
        if (changed == 0) throw new BusinessException(409, "秒杀名额已被其他订单占用");
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void releaseExpired() {
        TenantScheduler.run("flash-sale-release", tenantRegistry, this::releaseForTenant);
    }

    private void releaseForTenant() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.id,c.activity_id FROM flash_sale_claim c LEFT JOIN `order` o ON o.id=c.order_id " +
                        "WHERE c.status='ORDERED' AND (o.id IS NULL OR o.status='CANCELLED' OR (o.status='PENDING_PAYMENT' AND o.pay_deadline<CURRENT_TIMESTAMP))");
        for (var row : rows) {
            long claimId = ((Number) row.get("id")).longValue();
            long activityId = ((Number) row.get("activity_id")).longValue();
            if (jdbc.update("UPDATE flash_sale_claim SET status='RELEASED' WHERE id=? AND status='ORDERED'", claimId) == 1)
                jdbc.update("UPDATE flash_sale_activity SET remaining_stock=remaining_stock+1 WHERE id=?", activityId);
        }
    }

    private Map<String, Object> current(Long productId, LocalDateTime now, boolean lock) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT f.*,p.name AS product_name FROM flash_sale_activity f LEFT JOIN product p ON p.id=f.product_id " +
                        "WHERE f.product_id=? AND f.status=1 AND f.starts_at<=? AND f.ends_at>=? AND f.remaining_stock>0 " +
                        "ORDER BY f.starts_at,f.id LIMIT 1" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> row(rs), productId, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("name", rs.getString("name"));
        row.put("productId", rs.getLong("product_id"));
        row.put("productName", rs.getString("product_name"));
        row.put("price", rs.getBigDecimal("price"));
        row.put("totalStock", rs.getInt("total_stock"));
        row.put("remainingStock", rs.getInt("remaining_stock"));
        row.put("startsAt", rs.getTimestamp("starts_at"));
        row.put("endsAt", rs.getTimestamp("ends_at"));
        row.put("enabled", rs.getInt("status") == 1);
        row.put("createdAt", rs.getTimestamp("created_at"));
        return row;
    }

    private static Long positiveLong(Object value, String message) {
        try {
            long result = Long.parseLong(String.valueOf(value));
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (Exception error) { throw new BusinessException(400, message); }
    }

    private static int integer(Object value, int min, int max, String message) {
        try {
            int result = Integer.parseInt(String.valueOf(value));
            if (result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (Exception error) { throw new BusinessException(400, message); }
    }

    private static BigDecimal decimal(Object value, String message) {
        try {
            BigDecimal result = new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.UNNECESSARY);
            if (result.signum() <= 0 || result.compareTo(new BigDecimal("1000000")) > 0) throw new NumberFormatException();
            return Money.positive(result);
        } catch (Exception error) { throw new BusinessException(400, message); }
    }

    private static LocalDateTime time(Object value, String message) {
        try { return LocalDateTime.parse(String.valueOf(value)); }
        catch (Exception error) { throw new BusinessException(400, message + "，格式为 yyyy-MM-ddTHH:mm:ss"); }
    }

    private static String required(Object value, String message, int max) {
        String result = value == null ? "" : String.valueOf(value).trim();
        if (result.isBlank() || result.length() > max) throw new BusinessException(400, message);
        return result;
    }
}
