package com.delta.cs.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.player.service.PlayerDepositRefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cs/deposit-refunds")
@RequiredArgsConstructor
public class CsDepositRefundController {
    private final PlayerDepositRefundService service;

    public record ReviewRequest(boolean approve, String note) {}

    @GetMapping("/list")
    public R<PlayerDepositRefundService.PageResult> list(PageQuery query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.listForStaff(keyword, status, query.getPageNum(), query.getPageSize()));
    }

    @OpLog(module = "deposit-refund", operation = "审核押金退款")
    @PutMapping("/{id}/review")
    public R<PlayerDepositRefundService.RefundView> review(@PathVariable Long id,
            @RequestBody ReviewRequest request) {
        if (request == null) return R.fail(400, "审核参数不能为空");
        return R.ok(service.review(id, SecurityUtils.getUserId(), request.approve(), request.note()));
    }

    @OpLog(module = "deposit-refund", operation = "重试押金退款")
    @PostMapping("/{id}/retry")
    public R<PlayerDepositRefundService.RefundView> retry(@PathVariable Long id) {
        return R.ok(service.retry(id, SecurityUtils.getUserId()));
    }
}
