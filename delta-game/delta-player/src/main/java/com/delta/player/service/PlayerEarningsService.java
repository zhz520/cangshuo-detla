package com.delta.player.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderService;
import com.delta.pay.entity.Transaction;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.PlayerWallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Player earnings summary, pending settlement and detail queries. */
@Service
public class PlayerEarningsService {
    private final TransactionService transactionService;
    private final JdbcTemplate jdbc;
    private final PlayerWalletService playerWalletService;
    private final OrderService orderService;

    public PlayerEarningsService(TransactionService transactionService, JdbcTemplate jdbc,
                                 PlayerWalletService playerWalletService, OrderService orderService) {
        this.transactionService = transactionService;
        this.jdbc = jdbc;
        this.playerWalletService = playerWalletService;
        this.orderService = orderService;
    }

    /** Earnings summary. Deliberately not transactional so wallet reads keep their original lock behavior. */
    public Map<String, Object> summary(Long playerId) {
        Map<String, Object> data = new HashMap<>();

        // 钱包
        PlayerWallet wallet = playerWalletService.getByPlayerId(playerId);
        data.put("balance", wallet != null ? wallet.getBalance() : BigDecimal.ZERO);
        data.put("pendingIncome", wallet != null && wallet.getPendingIncome() != null ? wallet.getPendingIncome() : BigDecimal.ZERO);
        data.put("frozenAmount", wallet != null ? wallet.getFrozenAmount() : BigDecimal.ZERO);
        data.put("totalIncome", wallet != null ? wallet.getTotalIncome() : BigDecimal.ZERO);
        data.put("pendingRefundRecovery", jdbc.queryForObject(
                "SELECT COALESCE(SUM(remaining),0) FROM player_refund_recovery WHERE player_id=?",
                BigDecimal.class, playerId));
        BigDecimal rewardIncome = transactionService.getObj(new QueryWrapper<Transaction>()
                .select("COALESCE(SUM(amount),0)").eq("user_type", "PLAYER").eq("user_id", playerId)
                .in("type", "REWARD_INCOME", "REWARD_REVERSAL"), value -> new BigDecimal(value.toString()));
        data.put("rewardIncome", rewardIncome);

        // 今日收益
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        List<Transaction> todayTxns = transactionService.list(new LambdaQueryWrapper<Transaction>()
                .eq(Transaction::getUserType, "PLAYER")
                .eq(Transaction::getUserId, playerId)
                .in(Transaction::getType, "INCOME", "REWARD_INCOME", "REWARD_REVERSAL")
                .between(Transaction::getCreatedAt, todayStart, todayEnd));
        BigDecimal todayIncome = todayTxns.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        data.put("todayIncome", todayIncome);

        // 本周收益
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1);
        List<Transaction> weekTxns = transactionService.list(new LambdaQueryWrapper<Transaction>()
                .eq(Transaction::getUserType, "PLAYER")
                .eq(Transaction::getUserId, playerId)
                .in(Transaction::getType, "INCOME", "REWARD_INCOME", "REWARD_REVERSAL")
                .between(Transaction::getCreatedAt, LocalDateTime.of(weekStart, LocalTime.MIN), todayEnd));
        BigDecimal weekIncome = weekTxns.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        data.put("weekIncome", weekIncome);

        // 本月收益
        LocalDateTime monthStart = LocalDateTime.of(LocalDate.now().withDayOfMonth(1), LocalTime.MIN);
        List<Transaction> monthTxns = transactionService.list(new LambdaQueryWrapper<Transaction>()
                .eq(Transaction::getUserType, "PLAYER")
                .eq(Transaction::getUserId, playerId)
                .in(Transaction::getType, "INCOME", "REWARD_INCOME", "REWARD_REVERSAL")
                .between(Transaction::getCreatedAt, monthStart, todayEnd));
        BigDecimal monthIncome = monthTxns.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        data.put("monthIncome", monthIncome);

        // --- 订单统计 ---
        // 累计订单（已完成/已确认/已评价）
        long totalOrders = orderService.count(new LambdaQueryWrapper<Order>()
                .eq(Order::getPlayerId, playerId)
                .in(Order::getStatus, "COMPLETED", "CONFIRMED", "REVIEWED"));
        data.put("totalOrders", totalOrders);

        // 本月订单
        long monthOrders = orderService.count(new LambdaQueryWrapper<Order>()
                .eq(Order::getPlayerId, playerId)
                .in(Order::getStatus, "COMPLETED", "CONFIRMED", "REVIEWED")
                .ge(Order::getCreatedAt, monthStart));
        data.put("monthOrders", monthOrders);

        // 单均收益
        BigDecimal totalIncomeVal = wallet != null && wallet.getTotalIncome() != null ? wallet.getTotalIncome() : BigDecimal.ZERO;
        BigDecimal avgOrderAmount = totalOrders > 0
                ? totalIncomeVal.subtract(rewardIncome).divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        data.put("avgOrderAmount", avgOrderAmount);

        // 完成率 = 已完成订单 / 全部接过的订单（排除CANCELLED之外的所有）
        long allOrders = orderService.count(new LambdaQueryWrapper<Order>()
                .eq(Order::getPlayerId, playerId)
                .ne(Order::getStatus, "CANCELLED"));
        String completionRate = allOrders > 0
                ? String.valueOf(totalOrders * 100 / allOrders)
                : "0";
        data.put("completionRate", completionRate);

        return data;
    }

    /** 售后观察期内尚未转为可提现余额的订单收益。 */
    public List<Map<String, Object>> pending(Long playerId) {
        return jdbc.queryForList("SELECT s.order_id AS orderId,o.order_no AS orderNo,o.product_name AS productName," +
                "(s.amount-s.reversed_amount) AS amount,s.release_at AS releaseAt,s.status,s.attempts,s.last_error AS lastError " +
                "FROM player_income_settlement s JOIN `order` o ON o.id=s.order_id " +
                "WHERE s.player_id=? AND s.status='PENDING' ORDER BY s.release_at ASC,s.id ASC LIMIT 200", playerId);
    }

    /** 收益明细（支持日期范围过滤）。 */
    public Page<Transaction> detail(Long playerId, PageQuery query, String startDate, String endDate) {
        LambdaQueryWrapper<Transaction> w = new LambdaQueryWrapper<Transaction>()
                .eq(Transaction::getUserType, "PLAYER")
                .eq(Transaction::getUserId, playerId);
        if (startDate != null && !startDate.isEmpty()) {
            w.ge(Transaction::getCreatedAt, LocalDateTime.of(LocalDate.parse(startDate), LocalTime.MIN));
        }
        if (endDate != null && !endDate.isEmpty()) {
            w.le(Transaction::getCreatedAt, LocalDateTime.of(LocalDate.parse(endDate), LocalTime.MAX));
        }
        w.orderByDesc(Transaction::getCreatedAt);
        return transactionService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
    }
}
