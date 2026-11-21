package com.delta.user.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.user.service.UserEngagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Regular-member product favorites and player follows. */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class UserEngagementController {
    private final UserEngagementService engagement;

    @GetMapping("/favorites")
    public R<Page<Map<String, Object>>> favorites(PageQuery query) {
        return R.ok(engagement.favorites(SecurityUtils.getUserId(), query));
    }

    @GetMapping("/favorites/{productId}/status")
    public R<Map<String, Object>> favoriteStatus(@PathVariable Long productId) {
        requireId(productId);
        return R.ok(engagement.favoriteStatus(SecurityUtils.getUserId(), productId));
    }

    @PostMapping("/favorites/{productId}")
    public R<Map<String, Object>> favorite(@PathVariable Long productId) {
        requireId(productId);
        return R.ok(engagement.favorite(SecurityUtils.getUserId(), productId));
    }

    @DeleteMapping("/favorites/{productId}")
    public R<Map<String, Object>> unfavorite(@PathVariable Long productId) {
        requireId(productId);
        return R.ok(engagement.unfavorite(SecurityUtils.getUserId(), productId));
    }

    @GetMapping("/follows")
    public R<Page<Map<String, Object>>> follows(PageQuery query) {
        return R.ok(engagement.follows(SecurityUtils.getUserId(), query));
    }

    @GetMapping("/follows/{playerId}/status")
    public R<Map<String, Object>> followStatus(@PathVariable Long playerId) {
        requireId(playerId);
        return R.ok(engagement.followStatus(SecurityUtils.getUserId(), playerId));
    }

    @PostMapping("/follows/{playerId}")
    public R<Map<String, Object>> follow(@PathVariable Long playerId) {
        requireId(playerId);
        return R.ok(engagement.follow(SecurityUtils.getUserId(), playerId));
    }

    @DeleteMapping("/follows/{playerId}")
    public R<Map<String, Object>> unfollow(@PathVariable Long playerId) {
        requireId(playerId);
        return R.ok(engagement.unfollow(SecurityUtils.getUserId(), playerId));
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "编号无效");
    }
}
