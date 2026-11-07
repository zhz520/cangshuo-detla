package com.delta.admin.job;

import com.delta.admin.service.SettlementRecoveryService;
import com.delta.admin.listener.SettlementEventListener;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Releases pending rider income only after the after-sales hold has elapsed. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingIncomeReleaseTask {
    private final JdbcTemplate jdbc;
    private final PlayerWalletService wallets;
    private final TransactionService transactions;
    private final SettlementRecoveryService recovery;
    private final SettlementEventListener settlement;
    private final ApplicationEventPublisher events;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        TransactionTemplate retryTransaction = new TransactionTemplate(transactionManager);
        try {
            List<Long> unsettled = jdbc.queryForList("SELECT id FROM `order` WHERE status IN ('CONFIRMED','REVIEWED') " +
                    "AND settled=0 ORDER BY id LIMIT 50", Long.class);
            for (Long orderId : unsettled) {
                try { settlement.settleOrder(orderId); }
                catch (Exception error) { log.error("补结算订单失败 orderId={}", orderId, error); }
            }
        } catch (Exception error) {
            log.error("扫描未结算订单失败", error);
        }
        List<Long> ids;
        try {
            ids = jdbc.queryForList("SELECT id FROM player_income_settlement WHERE status='PENDING' " +
                    "AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 100", Long.class);
        } catch (Exception error) {
            log.error("读取待结算收益任务失败", error);
            return;
        }
        TransactionTemplate transaction = retryTransaction;
        for (Long id : ids) {
            try {
                transaction.executeWithoutResult(status -> releaseOne(id));
            } catch (Exception error) {
                log.error("释放待结算收益失败 settlementId={}", id, error);
                postpone(id, error);
            }
        }
    }

    void releaseOne(Long settlementId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM player_income_settlement WHERE id=? FOR UPDATE", settlementId);
        if (rows.isEmpty()) return;
        Map<String, Object> settlement = rows.get(0);
        if (!"PENDING".equals(String.valueOf(settlement.get("status")))) return;

        long orderId = ((Number) settlement.get("order_id")).longValue();
        long playerId = ((Number) settlement.get("player_id")).longValue();
        BigDecimal amount = (BigDecimal) settlement.get("amount");
        BigDecimal reversed = settlement.get("reversed_amount") == null
                ? BigDecimal.ZERO : (BigDecimal) settlement.get("reversed_amount");
        BigDecimal releasable = amount.subtract(reversed);
        if (releasable.signum() <= 0) {
            markCancelled(settlementId, "退款已冲正全部待结算收益");
            return;
        }

        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT status FROM `order` WHERE id=? FOR UPDATE", orderId);
        if (orders.isEmpty()) throw new BusinessException(409, "关联订单不存在");
        String orderStatus = String.valueOf(orders.get(0).get("status"));
        if (!java.util.Set.of("CONFIRMED", "REVIEWED").contains(orderStatus)) {
            postpone(settlementId, null);
            return;
        }

        Long activeComplaints = jdbc.queryForObject("SELECT COUNT(*) FROM complaint WHERE order_id=? " +
                "AND status IN ('PENDING','PROCESSING','APPEALING')", Long.class, orderId);
        if (activeComplaints != null && activeComplaints > 0) {
            postpone(settlementId, null);
            return;
        }

        PlayerWallet wallet = wallets.getByPlayerId(playerId);
        if (wallet == null) throw new BusinessException(409, "打手钱包不存在");
        BigDecimal pending = wallet.getPendingIncome() == null ? BigDecimal.ZERO : wallet.getPendingIncome();
        if (pending.compareTo(releasable) < 0)
            throw new BusinessException(409, "待结算余额与结算明细不一致，请人工核账");

        BigDecimal before = wallet.getBalance() == null ? BigDecimal.ZERO : wallet.getBalance();
        BigDecimal after = before.add(releasable);
        wallet.setPendingIncome(pending.subtract(releasable));
        wallet.setBalance(after);
        wallet.setTotalIncome((wallet.getTotalIncome() == null ? BigDecimal.ZERO : wallet.getTotalIncome()).add(releasable));
        wallets.updateById(wallet);
        transactions.record("INCOME", "PLAYER", playerId, releasable, before, after, orderId,
                null, null, "售后期结束，订单收益转为可提现余额");
        jdbc.update("UPDATE player_income_settlement SET status='RELEASED',released_at=CURRENT_TIMESTAMP," +
                        "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'", settlementId);
        recovery.recover(playerId, releasable);
        events.publishEvent(new BusinessEvent(this, "INCOME_SETTLED", "PLAYER", playerId, orderId,
                "订单收益 ¥" + releasable.stripTrailingZeros().toPlainString() + " 已转为可提现余额"));
    }

    private void postpone(Long settlementId, Exception error) {
        String message = error == null ? null : shorten(error.getMessage());
        jdbc.update("UPDATE player_income_settlement SET attempts=attempts+1,last_error=?,next_attempt_at=?," +
                        "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
                message, LocalDateTime.now().plusMinutes(5), settlementId);
    }

    private void markCancelled(Long settlementId, String reason) {
        jdbc.update("UPDATE player_income_settlement SET status='CANCELLED',last_error=?,updated_at=CURRENT_TIMESTAMP " +
                "WHERE id=? AND status='PENDING'", shorten(reason), settlementId);
    }

    private String shorten(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
