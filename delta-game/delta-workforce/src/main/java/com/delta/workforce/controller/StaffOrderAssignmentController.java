package com.delta.workforce.controller;

import com.delta.common.customer.CsRoutingService;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.player.service.OrderAssignmentCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/admin/order/{id}/assign", "/cs/order/{id}/assign"})
@RequiredArgsConstructor
public class StaffOrderAssignmentController {
    private final OrderAssignmentCandidateService assignments;
    private final CsRoutingService routing;

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.manage')")
    public R<OrderAssignmentCandidateService.Candidates> candidates(@PathVariable Long id, PageQuery page,
                                                                      @RequestParam(required = false) String keyword) {
        if ("CS".equals(SecurityUtils.getUserType())) routing.assertCsCanHandleOrder(id, SecurityUtils.getUserId());
        return R.ok(assignments.candidates(id, page.getPageNum(), page.getPageSize(), keyword));
    }
}
