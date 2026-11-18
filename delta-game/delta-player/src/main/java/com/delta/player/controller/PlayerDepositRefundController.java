package com.delta.player.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.player.service.PlayerDepositRefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/player/deposit/refund")
@RequiredArgsConstructor
public class PlayerDepositRefundController {
    private final PlayerDepositRefundService service;

    public record SubmitRequest(Long paymentId, String reason) {}

    @GetMapping("/context")
    public R<PlayerDepositRefundService.Context> context() {
        return R.ok(service.context(SecurityUtils.getUserId()));
    }

    @GetMapping("/list")
    public R<List<PlayerDepositRefundService.RefundView>> list() {
        return R.ok(service.listForPlayer(SecurityUtils.getUserId()));
    }

    @PostMapping
    public R<PlayerDepositRefundService.RefundView> submit(@RequestBody SubmitRequest request) {
        if (request == null || request.paymentId() == null) return R.fail(400, "请选择押金支付记录");
        return R.ok(service.submit(SecurityUtils.getUserId(), request.paymentId(), request.reason()));
    }

    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        service.cancel(SecurityUtils.getUserId(), id);
        return R.ok();
    }
}
