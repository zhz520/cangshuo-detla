package com.delta.user.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Regular-member product favorites and player follows. */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class UserEngagementController {
    private final JdbcTemplate jdbc;

    @GetMapping("/favorites")
    public R<Page<Map<String, Object>>> favorites(PageQuery query) {
        Long userId = SecurityUtils.getUserId();
        int pageNum = Math.max(1, query.getPageNum());
        int pageSize = Math.min(50, Math.max(1, query.getPageSize()));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite f JOIN product p ON p.id=f.product_id WHERE f.user_id=? AND p.deleted=0", Long.class, userId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.id,p.name,p.subtitle,p.cover_image AS coverImage,p.price,p.status,p.sales_count AS salesCount,f.created_at AS favoriteAt " +
                        "FROM user_favorite f JOIN product p ON p.id=f.product_id WHERE f.user_id=? AND p.deleted=0 " +
                        "ORDER BY f.created_at DESC LIMIT ? OFFSET ?", userId, pageSize, (pageNum - 1) * pageSize);
        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize, total == null ? 0 : total);
        page.setRecords(rows);
        return R.ok(page);
    }

    @GetMapping("/favorites/{productId}/status")
    public R<Map<String, Object>> favoriteStatus(@PathVariable Long productId) {
        requireId(productId);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite WHERE user_id=? AND product_id=?", Long.class, SecurityUtils.getUserId(), productId);
        return R.ok(Map.of("favorited", count != null && count > 0));
    }

    @PostMapping("/favorites/{productId}")
    public R<Map<String, Object>> favorite(@PathVariable Long productId) {
        requireId(productId);
        Integer available = jdbc.query("SELECT status FROM product WHERE id=? AND deleted=0", rs -> rs.next() ? rs.getInt(1) : null, productId);
        if (available == null) throw new BusinessException(404, "商品不存在");
        if (available != 1) throw new BusinessException(409, "商品已下架，暂时不能收藏");
        try {
            jdbc.update("INSERT INTO user_favorite(user_id,product_id) VALUES (?,?)", SecurityUtils.getUserId(), productId);
        } catch (DuplicateKeyException ignored) {
            // Idempotent: repeated taps keep the original favorite time.
        }
        return R.ok(Map.of("favorited", true));
    }

    @DeleteMapping("/favorites/{productId}")
    public R<Map<String, Object>> unfavorite(@PathVariable Long productId) {
        requireId(productId);
        jdbc.update("DELETE FROM user_favorite WHERE user_id=? AND product_id=?", SecurityUtils.getUserId(), productId);
        return R.ok(Map.of("favorited", false));
    }

    @GetMapping("/follows")
    public R<Page<Map<String, Object>>> follows(PageQuery query) {
        Long userId = SecurityUtils.getUserId();
        int pageNum = Math.max(1, query.getPageNum());
        int pageSize = Math.min(50, Math.max(1, query.getPageSize()));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM player_follow f JOIN player p ON p.id=f.player_id WHERE f.user_id=? AND p.deleted=0", Long.class, userId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.id,p.nickname,p.avatar,p.level,pl.name AS levelName,p.introduction,p.cover_image AS coverImage," +
                        "p.avg_rating AS avgRating,p.order_count AS orderCount,p.complete_rate AS completeRate,p.is_online AS isOnline,p.status,f.created_at AS followedAt " +
                        "FROM player_follow f JOIN player p ON p.id=f.player_id LEFT JOIN player_level pl ON pl.id=p.level AND pl.deleted=0 " +
                        "WHERE f.user_id=? AND p.deleted=0 ORDER BY f.created_at DESC LIMIT ? OFFSET ?", userId, pageSize, (pageNum - 1) * pageSize);
        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize, total == null ? 0 : total);
        page.setRecords(rows);
        return R.ok(page);
    }

    @GetMapping("/follows/{playerId}/status")
    public R<Map<String, Object>> followStatus(@PathVariable Long playerId) {
        requireId(playerId);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM player_follow WHERE user_id=? AND player_id=?", Long.class, SecurityUtils.getUserId(), playerId);
        return R.ok(Map.of("following", count != null && count > 0));
    }

    @PostMapping("/follows/{playerId}")
    public R<Map<String, Object>> follow(@PathVariable Long playerId) {
        requireId(playerId);
        List<Map<String, Object>> players = jdbc.queryForList("SELECT status FROM player WHERE id=? AND deleted=0", playerId);
        if (players.isEmpty()) throw new BusinessException(404, "打手不存在");
        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(players.get(0).get("status")))) throw new BusinessException(409, "当前打手暂不可关注");
        try {
            jdbc.update("INSERT INTO player_follow(user_id,player_id) VALUES (?,?)", SecurityUtils.getUserId(), playerId);
        } catch (DuplicateKeyException ignored) {
            // Idempotent: repeated taps keep the original follow time.
        }
        Long followers = jdbc.queryForObject("SELECT COUNT(*) FROM player_follow WHERE player_id=?", Long.class, playerId);
        return R.ok(Map.of("following", true, "followerCount", followers == null ? 0 : followers));
    }

    @DeleteMapping("/follows/{playerId}")
    public R<Map<String, Object>> unfollow(@PathVariable Long playerId) {
        requireId(playerId);
        jdbc.update("DELETE FROM player_follow WHERE user_id=? AND player_id=?", SecurityUtils.getUserId(), playerId);
        Long followers = jdbc.queryForObject("SELECT COUNT(*) FROM player_follow WHERE player_id=?", Long.class, playerId);
        return R.ok(Map.of("following", false, "followerCount", followers == null ? 0 : followers));
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "编号无效");
    }
}
