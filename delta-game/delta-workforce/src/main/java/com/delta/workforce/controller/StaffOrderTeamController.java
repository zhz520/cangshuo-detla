package com.delta.workforce.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.customer.CsRoutingService;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.service.OrderService;
import com.delta.player.service.OrderTeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/admin/order/{id}/teammates", "/cs/order/{id}/teammates"})
@RequiredArgsConstructor
public class StaffOrderTeamController {
    private final OrderTeamService teams;
    private final OrderService orders;
    private final CsRoutingService routing;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.read')")
    public R<List<OrderPlayer>> roster(@PathVariable Long id) {
        if ("CS".equals(SecurityUtils.getUserType())) routing.assertCsCanHandleOrder(id, SecurityUtils.getUserId());
        else if (orders.getById(id) == null) throw new BusinessException(404, "订单不存在");
        return R.ok(teams.roster(id));
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.manage')")
    public R<OrderTeamService.Candidates> candidates(@PathVariable Long id, PageQuery page,
                                                     @RequestParam(required = false) String keyword) {
        return R.ok(teams.candidates(id, actor(), page.getPageNum(), page.getPageSize(), keyword));
    }

    @PostMapping("/invite")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.manage')")
    @OpLog(module = "order", operation = "发出协同邀请")
    public R<Void> invite(@PathVariable Long id, @RequestBody OrderTeamService.Invite request) {
        teams.invite(id, actor(), request);
        return R.ok();
    }

    @PostMapping("/{memberId}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.manage')")
    @OpLog(module = "order", operation = "撤回协同邀请")
    public R<Void> revoke(@PathVariable Long id, @PathVariable Long memberId) {
        teams.revoke(id, actor(), memberId, null);
        return R.ok();
    }

    @PostMapping("/{memberId}/remove")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.manage')")
    @OpLog(module = "order", operation = "立即移除协同打手")
    public R<Void> remove(@PathVariable Long id, @PathVariable Long memberId, @RequestBody OrderTeamService.Reason request) {
        teams.remove(id, actor(), memberId, request == null ? null : request.reason());
        return R.ok();
    }

    private OrderTeamService.Actor actor() {
        return new OrderTeamService.Actor(SecurityUtils.getUserType(), SecurityUtils.getUserId());
    }
}
