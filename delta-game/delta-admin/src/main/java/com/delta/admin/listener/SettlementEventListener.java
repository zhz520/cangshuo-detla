package com.delta.admin.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.event.OrderConfirmedEvent;
import com.delta.common.exception.BusinessException;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderService;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerWalletService;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single, idempotent order-income settlement entry.
 *
 * <p>Earned income first enters {@code pending_income}. It becomes withdrawable only
 * after the configured after-sales hold expires and no complaint/refund is active.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementEventListener {
    private final OrderService orderService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final OrderPlayerService orderPlayerService;
    private final PlayerWalletService playerWalletService;
    private final SysConfigService sysConfigService;

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        settleOrder(event.getOrderId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void settleOrder(Long orderId) {
        Order order = orderService.getOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getId, orderId).last("FOR UPDATE"));
        if (order == null || order.getPlayerId() == null) {
            log.warn("结算跳过: 订单不存在或无打手, orderId={}", orderId);
            return;
        }
        if (Integer.valueOf(1).equals(order.getSettled())) return;
        if (!java.util.Set.of("CONFIRMED", "REVIEWED").contains(order.getStatus())) return;

        BigDecimal commissionRate = order.getCommissionRate();
        if (commissionRate == null) commissionRate = new BigDecimal(sysConfigService.getConfigValue("settlement.commission_rate", "0.2"));
        BigDecimal orderAmount = order.getAmount();
        com.delta.common.utils.Money.positive(orderAmount);
        if (commissionRate.signum() < 0 || commissionRate.compareTo(BigDecimal.ONE) > 0)
            throw new BusinessException("抽佣比例无效");

        BigDecimal commission = orderAmount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal playerTotalIncome = orderAmount.subtract(commission);
        var snapshot = jdbc.queryForList("SELECT cs_rate,cs_id FROM order_finance_snapshot WHERE order_id=?", orderId);
        Long csId = snapshot.isEmpty() || snapshot.get(0).get("cs_id") == null
                ? null : ((Number) snapshot.get(0).get("cs_id")).longValue();
        BigDecimal csRate = snapshot.isEmpty() || snapshot.get(0).get("cs_rate") == null
                ? BigDecimal.ZERO : (BigDecimal) snapshot.get(0).get("cs_rate");
        BigDecimal csIncome = csId == null ? BigDecimal.ZERO
                : commission.multiply(csRate).setScale(2, RoundingMode.HALF_UP);
        if (csIncome.signum() < 0 || csIncome.compareTo(commission) > 0)
            throw new BusinessException("客服提成超出俱乐部抽成");

        Integer ledgerExists = jdbc.queryForObject("SELECT COUNT(*) FROM order_settlement_ledger WHERE order_id=?", Integer.class, orderId);
        if (ledgerExists == null || ledgerExists == 0) {
            jdbc.update("INSERT INTO order_settlement_ledger(order_id,amount,club_amount,player_amount,cs_amount,cs_id) VALUES (?,?,?,?,?,?)",
                    orderId, orderAmount, commission.subtract(csIncome), playerTotalIncome, csIncome, csId);
        }

        Long primaryPlayerId = order.getPlayerId();
        List<OrderPlayer> teammates = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getRole, "TEAMMATE")
                .eq(OrderPlayer::getStatus, "ACCEPTED"));
        List<OrderPlayer> relayOuts = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getRole, "RELAY_OUT")
                .eq(OrderPlayer::getStatus, "ACCEPTED"));
        int sharingPeople = teammates.size() + relayOuts.size();

        var participantIds = new java.util.TreeSet<Long>();
        participantIds.add(primaryPlayerId);
        teammates.forEach(player -> participantIds.add(player.getPlayerId()));
        relayOuts.forEach(player -> participantIds.add(player.getPlayerId()));
        for (Long participant : participantIds) {
            if (playerWalletService.getByPlayerId(participant) == null) {
                playerWalletService.initWallet(participant);
                playerWalletService.getByPlayerId(participant);
            }
        }

        BigDecimal primaryIncome = playerTotalIncome;
        BigDecimal totalShares = java.util.stream.Stream.concat(teammates.stream(), relayOuts.stream())
                .map(player -> calcTeammateIncome(playerTotalIncome, player, sharingPeople))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalShares.signum() < 0 || totalShares.compareTo(playerTotalIncome) > 0)
            throw new BusinessException("协作分成总额超过可结算收益");

        LinkedHashMap<Long, SettlementShare> shares = new LinkedHashMap<>();
        for (OrderPlayer relayOut : relayOuts) {
            BigDecimal income = calcTeammateIncome(playerTotalIncome, relayOut, sharingPeople);
            if (income.signum() <= 0) continue;
            primaryIncome = primaryIncome.subtract(income);
            addShare(shares, relayOut.getPlayerId(), income, "RELAY_OUT");
            relayOut.setSettleAmount(income);
            relayOut.setSettledAt(LocalDateTime.now());
            orderPlayerService.updateById(relayOut);
        }
        for (OrderPlayer teammate : teammates) {
            BigDecimal income = calcTeammateIncome(playerTotalIncome, teammate, sharingPeople);
            if (income.signum() <= 0) continue;
            primaryIncome = primaryIncome.subtract(income);
            addShare(shares, teammate.getPlayerId(), income, "TEAMMATE");
            teammate.setSettleAmount(income);
            teammate.setSettledAt(LocalDateTime.now());
            orderPlayerService.updateById(teammate);
        }
        if (primaryIncome.signum() > 0) addShare(shares, primaryPlayerId, primaryIncome, "PRIMARY");

        List<OrderPlayer> primaryOps = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getPlayerId, primaryPlayerId)
                .eq(OrderPlayer::getRole, "PRIMARY")
                .orderByDesc(OrderPlayer::getId)
                .last("LIMIT 1"));
        if (!primaryOps.isEmpty()) {
            OrderPlayer primary = primaryOps.get(0);
            primary.setSettleAmount(primaryIncome);
            primary.setSettledAt(LocalDateTime.now());
            orderPlayerService.updateById(primary);
        }

        int holdHours = holdHours();
        LocalDateTime releaseAt = LocalDateTime.now().plusHours(holdHours);
        for (SettlementShare share : shares.values()) acquirePendingIncome(orderId, share, releaseAt);

        order.setSettled(1);
        order.setSettleAmount(playerTotalIncome);
        order.setSettleTime(LocalDateTime.now());
        orderService.updateById(order);
        log.info("订单收益进入待结算: orderId={}, amount={}, releaseAt={}, participants={}",
                orderId, playerTotalIncome, releaseAt, shares.size());
    }

    private void acquirePendingIncome(Long orderId, SettlementShare share, LocalDateTime releaseAt) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM player_income_settlement WHERE order_id=? AND player_id=?",
                Integer.class, orderId, share.playerId());
        if (exists != null && exists > 0) return;
        PlayerWallet wallet = playerWalletService.getByPlayerId(share.playerId());
        if (wallet == null) {
            playerWalletService.initWallet(share.playerId());
            wallet = playerWalletService.getByPlayerId(share.playerId());
        }
        BigDecimal pendingBefore = wallet.getPendingIncome() == null ? BigDecimal.ZERO : wallet.getPendingIncome();
        wallet.setPendingIncome(pendingBefore.add(share.amount()));
        playerWalletService.updateById(wallet);
        jdbc.update("INSERT INTO player_income_settlement(order_id,player_id,role,amount,reversed_amount,status,release_at,next_attempt_at,remark) " +
                        "VALUES (?,?,?,?,0,'PENDING',?,?,?)",
                orderId, share.playerId(), share.role(), share.amount(), releaseAt, releaseAt,
                "订单收益待售后观察期结算");
    }

    private void addShare(Map<Long, SettlementShare> shares, Long playerId, BigDecimal amount, String role) {
        shares.merge(playerId, new SettlementShare(playerId, amount, role),
                (old, added) -> new SettlementShare(playerId, old.amount().add(added.amount()),
                        "PRIMARY".equals(old.role()) ? old.role() : added.role()));
    }

    private BigDecimal calcTeammateIncome(BigDecimal playerTotalIncome, OrderPlayer teammate, int sharingPeople) {
        String splitType = teammate.getSplitType() == null ? "FIFTY_FIFTY" : teammate.getSplitType();
        return switch (splitType) {
            case "CUSTOM" -> teammate.getSplitAmount() == null ? BigDecimal.ZERO : teammate.getSplitAmount().setScale(2, RoundingMode.HALF_UP);
            case "FORTY_SIXTY" -> playerTotalIncome.multiply(new BigDecimal("0.4")).setScale(2, RoundingMode.HALF_UP);
            case "THIRTY_SEVENTY" -> playerTotalIncome.multiply(new BigDecimal("0.3")).setScale(2, RoundingMode.HALF_UP);
            default -> playerTotalIncome.divide(BigDecimal.valueOf(sharingPeople + 1), 2, RoundingMode.HALF_UP);
        };
    }

    private int holdHours() {
        try {
            return Math.max(0, Math.min(720,
                    Integer.parseInt(sysConfigService.getConfigValue("settlement.hold_hours", "72"))));
        } catch (Exception ignored) {
            return 72;
        }
    }

    private record SettlementShare(Long playerId, BigDecimal amount, String role) {}
}
