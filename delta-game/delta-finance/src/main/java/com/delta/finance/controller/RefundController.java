package com.delta.finance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.customer.CsRoutingService;
import com.delta.pay.refund.OrderRefundWorkflowService;
import com.delta.pay.refund.CustomerRefundView;
import com.delta.pay.refund.RefundCreateRequest;
import com.delta.pay.refund.RefundDecisionRequest;
import com.delta.pay.refund.RefundRequest;
import com.delta.pay.refund.RefundReviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RefundController {
    private final OrderRefundWorkflowService refunds;
    private final CsRoutingService csRouting;

    @PostMapping("/order/{orderId}/refunds")
    public R<CustomerRefundView> requestByCustomer(@PathVariable Long orderId,
                                                   @RequestBody RefundCreateRequest request) {
        return R.ok(CustomerRefundView.from(refunds.requestByCustomer(orderId, SecurityUtils.getUserId(),
                SecurityUtils.getUserType(), request)));
    }

    @GetMapping("/order/{orderId}/refunds")
    public R<List<CustomerRefundView>> customerHistory(@PathVariable Long orderId) {
        return R.ok(refunds.historyForCustomer(orderId, SecurityUtils.getUserId(), SecurityUtils.getUserType())
                .stream().map(CustomerRefundView::from).toList());
    }

    @OpLog(module = "refund", operation = "客服创建退款申请")
    @PostMapping("/cs/refunds/{orderId}")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.refund')")
    public R<RefundRequest> createByStaff(@PathVariable Long orderId,
                                           @RequestBody RefundCreateRequest request) {
        assertCsOrder(orderId);
        return R.ok(refunds.createByStaff(orderId, SecurityUtils.getUserId(),
                SecurityUtils.getUserType(), request));
    }

    @GetMapping("/cs/refunds")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.refund')")
    public R<Page<RefundRequest>> csList(PageQuery query,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String orderNo) {
        return R.ok(refunds.page(query.getPageNum(), query.getPageSize(), status, orderNo,
                "CS".equals(SecurityUtils.getUserType()) ? SecurityUtils.getUserId() : null));
    }

    @GetMapping("/cs/refunds/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.refund')")
    public R<RefundRequest> csDetail(@PathVariable Long id) {
        RefundRequest request = refunds.getRequired(id);
        assertCsOrder(request.getOrderId());
        return R.ok(request);
    }

    @GetMapping("/cs/refunds/{id}/audit")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.refund')")
    public R<List<java.util.Map<String, Object>>> audit(@PathVariable Long id) {
        assertCsOrder(refunds.getRequired(id).getOrderId());
        return R.ok(refunds.auditTrail(id));
    }

    @OpLog(module = "refund", operation = "客服开始退款初审")
    @PostMapping("/cs/refunds/{id}/review/start")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.refund')")
    public R<RefundRequest> startReview(@PathVariable Long id) {
        assertCsOrder(refunds.getRequired(id).getOrderId());
        return R.ok(refunds.startReview(id, SecurityUtils.getUserId(), SecurityUtils.getUserType()));
    }

    @OpLog(module = "refund", operation = "客服提交退款初审")
    @PostMapping("/cs/refunds/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','CS') and @staffPermissions.allowed('order.refund')")
    public R<RefundRequest> submitReview(@PathVariable Long id, @RequestBody RefundReviewRequest review) {
        assertCsOrder(refunds.getRequired(id).getOrderId());
        return R.ok(refunds.submitPreliminaryReview(id, SecurityUtils.getUserId(),
                SecurityUtils.getUserType(), review));
    }

    @GetMapping("/admin/refunds")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('order.refund')")
    public R<Page<RefundRequest>> adminList(PageQuery query,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String orderNo) {
        return R.ok(refunds.page(query.getPageNum(), query.getPageSize(), status, orderNo, null));
    }

    @GetMapping("/admin/refunds/{id}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('order.refund')")
    public R<RefundRequest> adminDetail(@PathVariable Long id) {
        return R.ok(refunds.getRequired(id));
    }

    @OpLog(module = "refund", operation = "管理员终审通过退款")
    @PostMapping("/admin/refunds/{id}/approve")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('order.refund')")
    public R<RefundRequest> approve(@PathVariable Long id, @RequestBody(required = false) RefundDecisionRequest decision) {
        return R.ok(refunds.approve(id, SecurityUtils.getUserId(), SecurityUtils.getUserType(), decision));
    }

    @OpLog(module = "refund", operation = "管理员驳回退款")
    @PostMapping("/admin/refunds/{id}/reject")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('order.refund')")
    public R<RefundRequest> reject(@PathVariable Long id, @RequestBody RefundDecisionRequest decision) {
        return R.ok(refunds.reject(id, SecurityUtils.getUserId(), SecurityUtils.getUserType(), decision));
    }

    @OpLog(module = "refund", operation = "管理员重试退款")
    @PostMapping("/admin/refunds/{id}/retry")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('order.refund')")
    public R<RefundRequest> retry(@PathVariable Long id) {
        return R.ok(refunds.retry(id, SecurityUtils.getUserId(), SecurityUtils.getUserType()));
    }

    private void assertCsOrder(Long orderId) {
        if ("CS".equals(SecurityUtils.getUserType())) {
            csRouting.assertCsCanHandleOrder(orderId, SecurityUtils.getUserId());
        }
    }
}
