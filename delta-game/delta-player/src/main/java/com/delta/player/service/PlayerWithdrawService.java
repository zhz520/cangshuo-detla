package com.delta.player.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.pay.service.PaymentService;
import com.delta.pay.service.TransactionService;
import com.delta.player.dto.WithdrawRequest;
import com.delta.player.entity.Player;
import com.delta.player.entity.PlayerAccount;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.entity.Withdraw;
import com.delta.system.service.SysConfigService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Player withdrawal application, listing and deposit gate. */
public class PlayerWithdrawService {
    private final WithdrawService withdrawService;
    private final JdbcTemplate jdbc;
    private final PlayerService playerService;
    private final PlayerWalletService playerWalletService;
    private final TransactionService transactionService;
    private final ApplicationEventPublisher eventPublisher;
    private final SysConfigService sysConfigService;
    private final PlayerAccountService accounts;
    private final PaymentService paymentService;
    private final PlayerIdentityNameService identityNames;

    public PlayerWithdrawService(WithdrawService withdrawService, JdbcTemplate jdbc, PlayerService playerService,
                                 PlayerWalletService playerWalletService, TransactionService transactionService,
                                 ApplicationEventPublisher eventPublisher, SysConfigService sysConfigService,
                                 PlayerAccountService accounts, PaymentService paymentService,
                                 PlayerIdentityNameService identityNames) {
        this.withdrawService = withdrawService;
        this.jdbc = jdbc;
        this.playerService = playerService;
        this.playerWalletService = playerWalletService;
        this.transactionService = transactionService;
        this.eventPublisher = eventPublisher;
        this.sysConfigService = sysConfigService;
        this.accounts = accounts;
        this.paymentService = paymentService;
        this.identityNames = identityNames;
    }

    public WithdrawDepositPolicy.Status depositStatus(Long playerId) {
        return WithdrawDepositPolicy.evaluate(playerId, jdbc, sysConfigService, paymentService);
    }

    public Page<Withdraw> list(Long playerId, PageQuery query) {
        return withdrawService.page(new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<Withdraw>().eq(Withdraw::getPlayerId, playerId).orderByDesc(Withdraw::getCreatedAt));
    }

    public Withdraw detail(Long playerId, Long id) {
        Withdraw w = withdrawService.getById(id);
        if (w == null || !w.getPlayerId().equals(playerId)) {
            throw new BusinessException("记录不存在");
        }
        return w;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void apply(Long playerId, WithdrawRequest req) {
        if (jdbc.queryForList("SELECT id FROM player WHERE id=? AND deleted=0 FOR UPDATE", playerId).isEmpty())
            throw new BusinessException(404, "打手不存在");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM player_refund_recovery WHERE player_id=? AND remaining>0", Long.class, playerId) > 0)
            throw new BusinessException(409, "存在退款待追回款，请先核对结算台账");
        com.delta.common.utils.Money.positive(req.getAmount());
        if (playerWalletService.getByPlayerId(playerId) == null) throw new BusinessException("钱包不存在");

        // 0. 冻结期间不允许提现
        Player player = playerService.getById(playerId);
        if (player == null || !"ACTIVE".equals(player.getStatus())) {
            throw new BusinessException("账号已被冻结，冻结期间无法提现");
        }
        var deposit = depositStatus(playerId);
        if (deposit.needsPayment())
            throw new BusinessException(409, "提现前需补缴押金" + deposit.outstandingAmount().stripTrailingZeros().toPlainString() + "元");

        // 1. 最低提现金额校验
        String minStr = sysConfigService.getConfigValue("withdraw.min_amount", "10");
        BigDecimal minAmount = new BigDecimal(minStr);
        if (req.getAmount() == null || req.getAmount().compareTo(minAmount) < 0) {
            throw new BusinessException(400, "最低提现金额为" + minAmount + "元");
        }

        doWithdraw(playerId, req);
    }

    private void doWithdraw(Long playerId, WithdrawRequest req) {
        var account = req.getAccountId() == null ? null : accounts.getOne(new LambdaQueryWrapper<PlayerAccount>()
                .eq(PlayerAccount::getId, req.getAccountId()).eq(PlayerAccount::getPlayerId, playerId).last("FOR UPDATE"));
        if (account == null) throw new BusinessException(404, "收款账户不存在");
        if (identityNames.required()) identityNames.requireMatches(playerId, account.getAccountName(), "提现");
        // 3. 锁钱包后再校验余额和当日次数，避免并发提现绕过限额。
        PlayerWallet wallet = playerWalletService.getOne(new LambdaQueryWrapper<PlayerWallet>()
                .eq(PlayerWallet::getPlayerId, playerId).last("FOR UPDATE"));
        if (wallet == null || wallet.getBalance().compareTo(req.getAmount()) < 0) {
            throw new BusinessException(409, "余额不足");
        }

        int maxDailyCount = Integer.parseInt(sysConfigService.getConfigValue("withdraw.max_daily_count", "3"));
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todayCount = withdrawService.count(new LambdaQueryWrapper<Withdraw>()
                .eq(Withdraw::getPlayerId, playerId)
                .ge(Withdraw::getCreatedAt, todayStart)
                .notIn(Withdraw::getStatus, "REJECTED", "CANCELLED"));
        if (todayCount >= maxDailyCount) {
            throw new BusinessException(409, "每日最多提现" + maxDailyCount + "次");
        }

        BigDecimal balanceBefore = wallet.getBalance();

        // 4. 扣减余额，增加冻结
        wallet.setBalance(wallet.getBalance().subtract(req.getAmount()));
        wallet.setFrozenAmount(wallet.getFrozenAmount().add(req.getAmount()));
        playerWalletService.updateById(wallet);

        // 5. 创建提现记录
        Withdraw withdraw = new Withdraw();
        withdraw.setPlayerId(playerId);
        withdraw.setAccountId(req.getAccountId());
        withdraw.setAccountTypeSnapshot(account.getType());
        withdraw.setAccountNameSnapshot(account.getAccountName());
        withdraw.setAccountNoSnapshot(account.getAccountNo());
        withdraw.setAccountQrcodeSnapshot(account.getQrcodeUrl());
        withdraw.setAmount(req.getAmount());
        withdraw.setStatus("PENDING");
        withdrawService.save(withdraw);

        // 6. 创建交易记录
        transactionService.record(
                "WITHDRAW",
                "PLAYER",
                playerId,
                req.getAmount().negate(), // 提现为负数
                balanceBefore,
                wallet.getBalance(),
                null,
                null,
                withdraw.getId(),
                "提现申请"
        );

        // 7. 通知管理员有新提现待处理
        eventPublisher.publishEvent(new BusinessEvent(this, "WITHDRAW_APPLY",
                "ADMIN", null, withdraw.getId(), "新提现申请待处理，金额: " + req.getAmount() + "元"));
    }
}
