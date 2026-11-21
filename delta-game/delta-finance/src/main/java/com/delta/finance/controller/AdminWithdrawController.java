package com.delta.finance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.finance.service.WithdrawReviewService;
import com.delta.player.entity.Player;
import com.delta.player.entity.PlayerAccount;
import com.delta.player.entity.Withdraw;
import com.delta.player.service.PlayerService;
import com.delta.player.service.PlayerAccountService;
import com.delta.player.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/withdraw")
@RequiredArgsConstructor
public class AdminWithdrawController {
    private final WithdrawService withdrawService;
    private final PlayerService playerService;
    private final PlayerAccountService playerAccountService;
    private final WithdrawReviewService withdrawReviewService;

    @GetMapping("/list")
    public R<Page<Withdraw>> list(PageQuery query, @RequestParam(required = false) Long taskId, @RequestParam(value = "status", required = false) String status) {
        Page<Withdraw> page = withdrawService.page(new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<Withdraw>()
                .eq(taskId != null, Withdraw::getId, taskId).eq(status != null && !status.isEmpty(), Withdraw::getStatus, status)
                        .orderByDesc(Withdraw::getCreatedAt));
        // 填充打手信息和收款账户信息
        for (Withdraw w : page.getRecords()) {
            if (w.getPlayerId() != null) {
                Player player = playerService.getById(w.getPlayerId());
                if (player != null) {
                    w.setPlayerName(player.getNickname());
                    w.setPlayerPhone(player.getPhone());
                }
            }
            if(w.getAccountNoSnapshot()!=null) {
                w.setAccountType(w.getAccountTypeSnapshot());w.setAccountNo(w.getAccountNoSnapshot());
                w.setAccountName(w.getAccountNameSnapshot());w.setQrcodeUrl(w.getAccountQrcodeSnapshot());
            } else if (w.getAccountId() != null) {
                PlayerAccount account = playerAccountService.getById(w.getAccountId());
                if (account != null) {
                    w.setAccountType(account.getType());
                    w.setAccountNo(account.getAccountNo());
                    w.setAccountName(account.getAccountName());
                    w.setQrcodeUrl(account.getQrcodeUrl());
                }
            }
        }
        return R.ok(page);
    }

    @OpLog(module = "withdraw", operation = "处理提现")
    @PutMapping("/{id}/process")
    public R<Void> process(@PathVariable Long id, @RequestBody Withdraw w) {
        withdrawReviewService.approve(id, w);
        return R.ok();
    }

    @OpLog(module = "withdraw", operation = "拒绝提现")
    @PutMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @RequestParam("reason") String reason) {
        withdrawReviewService.reject(id, reason, WithdrawReviewService.RejectValidation.RECORD_FIRST);
        return R.ok();
    }
}
