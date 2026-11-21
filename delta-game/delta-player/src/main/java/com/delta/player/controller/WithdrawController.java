package com.delta.player.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.player.dto.WithdrawRequest;
import com.delta.player.entity.Withdraw;
import com.delta.player.service.PlayerWithdrawService;
import com.delta.player.service.WithdrawDepositPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/player/withdraw")
@RequiredArgsConstructor
public class WithdrawController {
    private final PlayerWithdrawService withdraws;

    @GetMapping("/deposit-status")
    public R<WithdrawDepositPolicy.Status> depositStatus() {
        return R.ok(withdraws.depositStatus(SecurityUtils.getUserId()));
    }

    @GetMapping({"" , "/list"})
    public R<Page<Withdraw>> list(PageQuery query) {
        return R.ok(withdraws.list(SecurityUtils.getUserId(), query));
    }

    @GetMapping("/{id}")
    public R<Withdraw> detail(@PathVariable Long id) {
        return R.ok(withdraws.detail(SecurityUtils.getUserId(), id));
    }

    @PostMapping
    public R<Void> apply(@RequestBody WithdrawRequest req) {
        withdraws.apply(SecurityUtils.getUserId(), req);
        return R.ok();
    }
}
