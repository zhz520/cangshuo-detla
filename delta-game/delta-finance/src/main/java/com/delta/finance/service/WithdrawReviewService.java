package com.delta.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.entity.Withdraw;
import com.delta.player.service.PlayerWalletService;
import com.delta.player.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Keeps wallet, withdrawal decision and ledger changes in one transaction for both staff entry points. */
@Service
@RequiredArgsConstructor
public class WithdrawReviewService {
    private final WithdrawService withdrawService;
    private final PlayerWalletService playerWalletService;
    private final TransactionService transactionService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, Withdraw w) {
        Withdraw withdraw = withdrawService.getOne(new LambdaQueryWrapper<Withdraw>().eq(Withdraw::getId,id).last("FOR UPDATE"));
        if (withdraw == null || !"PENDING".equals(withdraw.getStatus()))
            throw new BusinessException("提现记录不存在或已处理");

        PlayerWallet wallet = playerWalletService.getByPlayerId(withdraw.getPlayerId());
        if (wallet == null || wallet.getFrozenAmount() == null || wallet.getFrozenAmount().compareTo(withdraw.getAmount()) < 0)
            throw new BusinessException(409, "冻结资金与提现记录不一致，请核对台账");
        LocalDateTime processedAt = validateReview(w.getProofImage(), w.getPayMethod(), w.getProcessedAt(), withdraw.getCreatedAt());

        withdraw.setStatus("COMPLETED");
        withdraw.setPayMethod(w.getPayMethod());
        withdraw.setProofImage(w.getProofImage());
        withdraw.setProcessedBy(SecurityUtils.getUserId());
        withdraw.setProcessedAt(processedAt);
        withdrawService.updateById(withdraw);

        BigDecimal balanceBefore = wallet.getBalance();
        wallet.setFrozenAmount(wallet.getFrozenAmount().subtract(withdraw.getAmount()));
        playerWalletService.updateById(wallet);
        transactionService.record("WITHDRAW_COMPLETE", "PLAYER", withdraw.getPlayerId(),
                withdraw.getAmount().negate(), balanceBefore, wallet.getBalance(),
                null, null, withdraw.getId(), "提现完成");

        eventPublisher.publishEvent(new BusinessEvent(this, "WITHDRAW_COMPLETED",
                "PLAYER", withdraw.getPlayerId(), withdraw.getId(),
                "提现已完成，金额 " + withdraw.getAmount() + " 元，请查看打款凭证"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason, RejectValidation validation) {
        if (validation == RejectValidation.REASON_FIRST) reason = validatedReason(reason);
        Withdraw withdraw = withdrawService.getOne(new LambdaQueryWrapper<Withdraw>().eq(Withdraw::getId,id).last("FOR UPDATE"));
        if (withdraw == null || !"PENDING".equals(withdraw.getStatus()))
            throw new BusinessException("提现记录不存在或已处理");
        if (validation == RejectValidation.RECORD_FIRST) reason = validatedReason(reason);
        PlayerWallet wallet = playerWalletService.getByPlayerId(withdraw.getPlayerId());
        if (wallet == null || wallet.getFrozenAmount() == null || wallet.getFrozenAmount().compareTo(withdraw.getAmount()) < 0)
            throw new BusinessException(409, "冻结资金与提现记录不一致，请核对台账");

        withdraw.setStatus("REJECTED");
        withdraw.setRejectReason(reason);
        withdraw.setProcessedBy(SecurityUtils.getUserId());
        withdraw.setProcessedAt(LocalDateTime.now());
        withdrawService.updateById(withdraw);

        BigDecimal balanceBefore = wallet.getBalance();
        wallet.setFrozenAmount(wallet.getFrozenAmount().subtract(withdraw.getAmount()));
        wallet.setBalance(wallet.getBalance().add(withdraw.getAmount()));
        playerWalletService.updateById(wallet);
        transactionService.record("WITHDRAW_REJECT", "PLAYER", withdraw.getPlayerId(),
                withdraw.getAmount(), balanceBefore, wallet.getBalance(),
                null, null, withdraw.getId(), "提现被拒绝，资金退回: " + reason);

        eventPublisher.publishEvent(new BusinessEvent(this, "WITHDRAW_REJECTED",
                "PLAYER", withdraw.getPlayerId(), withdraw.getId(),
                "提现被拒绝，原因：" + reason + "，资金已退回余额"));
    }

    // Keep the existing API error precedence for each controller.
    public enum RejectValidation { RECORD_FIRST, REASON_FIRST }

    private static String validatedReason(String reason) {
        reason = reason == null ? "" : reason.trim();
        if (reason.isEmpty() || reason.length() > 500) throw new BusinessException(400, "拒绝原因须为1至500字");
        return reason;
    }

    private static LocalDateTime validateReview(String proofImage, String remark, LocalDateTime processedAt, LocalDateTime createdAt) {
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
