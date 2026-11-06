package com.delta.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.Player;
import com.delta.player.entity.PlayerAccount;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.entity.Withdraw;
import com.delta.player.service.PlayerService;
import com.delta.player.service.PlayerAccountService;
import com.delta.player.service.PlayerWalletService;
import com.delta.player.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/cs/withdraw")
@RequiredArgsConstructor
public class CsWithdrawController {
    private final WithdrawService withdrawService;
    private final PlayerService playerService;
    private final PlayerAccountService playerAccountService;
    private final PlayerWalletService playerWalletService;
    private final TransactionService transactionService;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping("/list")
    public R<Page<Withdraw>> list(PageQuery query, @RequestParam(required = false) Long taskId, @RequestParam(value = "status", required = false) String status) {
        Page<Withdraw> page = withdrawService.page(new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<Withdraw>()
                .eq(taskId != null, Withdraw::getId, taskId).eq(status != null && !status.isEmpty(), Withdraw::getStatus, status)
                        .orderByDesc(Withdraw::getCreatedAt));
        for (Withdraw w : page.getRecords()) {
            if (w.getPlayerId() != null) {
                Player player = playerService.getById(w.getPlayerId());
                if (player != null) {
                    w.setPlayerName(player.getNickname());
                    w.setPlayerPhone(player.getPhone());
                }
            }
            if(w.getAccountNoSnapshot()!=null) {
                w.setAccountType(w.getAccountTypeSnapshot());w.setAccountNo(w.getAccountNoSnapshot());w.setAccountName(w.getAccountNameSnapshot());w.setQrcodeUrl(w.getAccountQrcodeSnapshot());
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

    @GetMapping("/{id}")
    public R<Withdraw> detail(@PathVariable Long id) {
        return R.ok(withdrawService.getById(id));
    }

    @OpLog(module = "withdraw", operation = "审批提现")
    @PutMapping("/{id}/approve")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> approve(@PathVariable Long id, @RequestBody Withdraw w) {
        Withdraw withdraw = withdrawService.getOne(new LambdaQueryWrapper<Withdraw>().eq(Withdraw::getId,id).last("FOR UPDATE"));
        if (withdraw == null || !"PENDING".equals(withdraw.getStatus())) {
            throw new BusinessException("提现记录不存在或已处理");
        }
        PlayerWallet wallet = playerWalletService.getByPlayerId(withdraw.getPlayerId());
        if (wallet == null || wallet.getFrozenAmount() == null || wallet.getFrozenAmount().compareTo(withdraw.getAmount()) < 0)
            throw new BusinessException(409, "冻结资金与提现记录不一致，请核对台账");
        LocalDateTime processedAt = validateReview(w.getProofImage(), w.getPayMethod(), w.getProcessedAt(), withdraw.getCreatedAt());

        // 1. 更新提现记录
        withdraw.setStatus("COMPLETED");
        withdraw.setPayMethod(w.getPayMethod());
        withdraw.setProofImage(w.getProofImage());
        withdraw.setProcessedBy(SecurityUtils.getUserId());
        withdraw.setProcessedAt(processedAt);
        withdrawService.updateById(withdraw);

        // 2. 扣除冻结金额
        BigDecimal balanceBefore = wallet.getBalance();
        wallet.setFrozenAmount(wallet.getFrozenAmount().subtract(withdraw.getAmount()));
        playerWalletService.updateById(wallet);

        // 3. 创建交易记录确认
        transactionService.record("WITHDRAW_COMPLETE", "PLAYER", withdraw.getPlayerId(),
                withdraw.getAmount().negate(), balanceBefore, wallet.getBalance(),
                null, null, withdraw.getId(), "提现完成");

        // 4. 通知打手
        eventPublisher.publishEvent(new BusinessEvent(this, "WITHDRAW_COMPLETED",
                "PLAYER", withdraw.getPlayerId(), withdraw.getId(),
                "提现已完成，金额 " + withdraw.getAmount() + " 元，请查看打款凭证"));
        return R.ok();
    }

    @OpLog(module = "withdraw", operation = "拒绝提现")
    @PutMapping("/{id}/reject")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> reject(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        reason = reason == null ? "" : reason.trim();
        if (reason.isEmpty() || reason.length() > 500) throw new BusinessException(400, "拒绝原因须为1至500字");
        Withdraw withdraw = withdrawService.getOne(new LambdaQueryWrapper<Withdraw>().eq(Withdraw::getId,id).last("FOR UPDATE"));
        if (withdraw == null || !"PENDING".equals(withdraw.getStatus())) {
            throw new BusinessException("提现记录不存在或已处理");
        }
        PlayerWallet wallet = playerWalletService.getByPlayerId(withdraw.getPlayerId());
        if (wallet == null || wallet.getFrozenAmount() == null || wallet.getFrozenAmount().compareTo(withdraw.getAmount()) < 0)
            throw new BusinessException(409, "冻结资金与提现记录不一致，请核对台账");

        // 1. 更新提现记录
        withdraw.setStatus("REJECTED");
        withdraw.setRejectReason(reason);
        withdraw.setProcessedBy(SecurityUtils.getUserId());
        withdraw.setProcessedAt(LocalDateTime.now());
        withdrawService.updateById(withdraw);

        // 2. 资金退回：frozen → balance
        BigDecimal balanceBefore = wallet.getBalance();
        wallet.setFrozenAmount(wallet.getFrozenAmount().subtract(withdraw.getAmount()));
        wallet.setBalance(wallet.getBalance().add(withdraw.getAmount()));
        playerWalletService.updateById(wallet);

        // 3. 创建退回交易记录
        transactionService.record("WITHDRAW_REJECT", "PLAYER", withdraw.getPlayerId(),
                withdraw.getAmount(), balanceBefore, wallet.getBalance(),
                null, null, withdraw.getId(), "提现被拒绝，资金退回: " + reason);

        // 4. 通知打手
        eventPublisher.publishEvent(new BusinessEvent(this, "WITHDRAW_REJECTED",
                "PLAYER", withdraw.getPlayerId(), withdraw.getId(),
                "提现被拒绝，原因：" + reason + "，资金已退回余额"));
        return R.ok();
    }

    private LocalDateTime validateReview(String proofImage, String remark, LocalDateTime processedAt, LocalDateTime createdAt) {
        String proof = proofImage == null ? "" : proofImage.trim();
        if (proof.isEmpty() || proof.length() > 512 || !(proof.startsWith("/file/") || proof.startsWith("https://")))
            throw new BusinessException(400, "请上传有效的打款凭证");
        if (remark != null && remark.length() > 200) throw new BusinessException(400, "打款备注不能超过200字");
        if (processedAt == null) return LocalDateTime.now();
        if ((createdAt != null && processedAt.isBefore(createdAt)) || processedAt.isAfter(LocalDateTime.now().plusMinutes(5)))
            throw new BusinessException(400, "打款时间须在申请时间之后且不能晚于当前时间");
        return processedAt;
    }
}
