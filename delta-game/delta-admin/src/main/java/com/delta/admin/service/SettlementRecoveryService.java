package com.delta.admin.service;

import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.pay.service.TransactionService;
import com.delta.player.service.PlayerWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SettlementRecoveryService {
 private final JdbcTemplate jdbc;
 private final PlayerWalletService wallets;
 private final TransactionService transactions;
 @EventListener
 @Transactional(rollbackFor=Exception.class)
 public void refunded(BusinessEvent event){
  if(!"ORDER_REFUNDED".equals(event.getEventType())||event.getOrderId()==null)return;
  long id=event.getOrderId();var orders=jdbc.queryForList("SELECT amount,settled FROM `order` WHERE id=? FOR UPDATE",id);
  if(orders.isEmpty()||!("1".equals(String.valueOf(orders.get(0).get("settled")))||Boolean.TRUE.equals(orders.get(0).get("settled"))))return;
  BigDecimal amount=(BigDecimal)orders.get(0).get("amount");if(amount.signum()<=0)throw new BusinessException("订单结算金额异常");
  BigDecimal refunded=jdbc.queryForObject("SELECT COALESCE(SUM(refund_amount),0) FROM payment WHERE order_id=? AND status='REFUNDED'",BigDecimal.class,id).min(amount);
  if(refunded.signum()<=0)return;
  Map<Long,Map<String,Object>> settlements=new HashMap<>();
  try {
   for(var row:jdbc.queryForList("SELECT player_id,amount,reversed_amount,status FROM player_income_settlement WHERE order_id=? ORDER BY player_id FOR UPDATE",id))
    settlements.put(((Number)row.get("player_id")).longValue(),row);
  } catch (Exception ignored) {
   // Legacy databases that have not created the settlement table use transaction history below.
  }
  List<Map<String,Object>> incomes;
  if(!settlements.isEmpty()) {
   incomes=new ArrayList<>();
   for(var entry:settlements.entrySet())incomes.add(Map.of("user_id",entry.getKey(),"income",entry.getValue().get("amount")));
  } else {
   incomes=jdbc.queryForList("SELECT user_id,SUM(amount) AS income FROM `transaction` WHERE user_type='PLAYER' AND type='INCOME' AND related_order_id=? GROUP BY user_id ORDER BY user_id",id);
  }
  boolean exactSettlement=!settlements.isEmpty();
  BigDecimal cumulativeIncome=BigDecimal.ZERO,previousTarget=BigDecimal.ZERO;
  for(var income:incomes){
   long player=((Number)income.get("user_id")).longValue();BigDecimal original=(BigDecimal)income.get("income");
   BigDecimal expected;
   if(exactSettlement) expected=share(original,refunded,amount);
   else { cumulativeIncome=cumulativeIncome.add(original);BigDecimal target=share(cumulativeIncome,refunded,amount);expected=target.subtract(previousTarget);previousTarget=target; }
   var previous=jdbc.queryForList("SELECT refund_share,recovered,remaining FROM player_refund_recovery WHERE order_id=? AND player_id=? FOR UPDATE",id,player);
   BigDecimal old=previous.isEmpty()?BigDecimal.ZERO:(BigDecimal)previous.get(0).get("refund_share");
   BigDecimal oldRecovered=previous.isEmpty()?BigDecimal.ZERO:(BigDecimal)previous.get(0).get("recovered");
   BigDecimal delta=expected.subtract(old);if(delta.signum()<=0)continue;
   var wallet=wallets.getByPlayerId(player);if(wallet==null)throw new BusinessException("打手钱包缺失，请核对退款账务");
   var settlement=settlements.get(player);
   BigDecimal pendingDeduction=BigDecimal.ZERO;
   if(settlement!=null&&"PENDING".equals(String.valueOf(settlement.get("status")))){
    BigDecimal pending=wallet.getPendingIncome()==null?BigDecimal.ZERO:wallet.getPendingIncome();
    pendingDeduction=delta.min(pending).min(original.subtract((BigDecimal)settlement.get("reversed_amount")));
    if(pendingDeduction.signum()>0){
     wallet.setPendingIncome(pending.subtract(pendingDeduction));wallets.updateById(wallet);
     transactions.record("REFUND_PENDING","PLAYER",player,pendingDeduction.negate(),wallet.getBalance(),wallet.getBalance(),id,null,null,"售后期内退款，追回待结算收益");
    }
   }
   BigDecimal before=wallet.getBalance(),balanceDeduction=delta.subtract(pendingDeduction).min(before.max(BigDecimal.ZERO));
   if(balanceDeduction.signum()>0){wallet.setBalance(before.subtract(balanceDeduction));wallets.updateById(wallet);transactions.record("REFUND","PLAYER",player,balanceDeduction.negate(),before,wallet.getBalance(),id,null,null,"实际退款按已结算分成追回");}
   BigDecimal recovered=oldRecovered.add(pendingDeduction).add(balanceDeduction);
   BigDecimal remaining=expected.subtract(recovered).max(BigDecimal.ZERO);
   if(previous.isEmpty())jdbc.update("INSERT INTO player_refund_recovery(order_id,player_id,refund_share,recovered,remaining) VALUES (?,?,?,?,?)",id,player,expected,recovered,remaining);
   else jdbc.update("UPDATE player_refund_recovery SET refund_share=?,recovered=?,remaining=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND player_id=?",expected,recovered,remaining,id,player);
   if(settlement!=null){
    String nextStatus=expected.compareTo(original)>=0?"CANCELLED":String.valueOf(settlement.get("status"));
    jdbc.update("UPDATE player_income_settlement SET reversed_amount=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND player_id=?",expected,nextStatus,id,player);
   }
  }
  var ledger=jdbc.queryForList("SELECT * FROM order_settlement_ledger WHERE order_id=?",id);
  if(!ledger.isEmpty()){
   var row=ledger.get(0);BigDecimal player=share((BigDecimal)row.get("player_amount"),refunded,amount),cs=share((BigDecimal)row.get("cs_amount"),refunded,amount).min(refunded.subtract(player)),club=refunded.subtract(player).subtract(cs);
   if(jdbc.update("UPDATE order_settlement_refund SET refund_amount=?,club_refund=?,player_refund=?,cs_refund=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=?",refunded,club,player,cs,id)==0)jdbc.update("INSERT INTO order_settlement_refund(order_id,refund_amount,club_refund,player_refund,cs_refund) VALUES (?,?,?,?,?)",id,refunded,club,player,cs);
  }
 }
 /** Offset outstanding refund debt against newly credited income, with a separate transaction per source order. */
 @Transactional(rollbackFor=Exception.class)
 public void recover(Long playerId,BigDecimal available){
  var wallet=wallets.getByPlayerId(playerId);if(wallet==null)return;
  BigDecimal budget=available.min(wallet.getBalance());
  for(var row:jdbc.queryForList("SELECT order_id,remaining FROM player_refund_recovery WHERE player_id=? AND remaining>0 ORDER BY order_id FOR UPDATE",playerId)){
   if(budget.signum()<=0)break;BigDecimal take=((BigDecimal)row.get("remaining")).min(budget),before=wallet.getBalance();
   wallet.setBalance(before.subtract(take));wallets.updateById(wallet);
   jdbc.update("UPDATE player_refund_recovery SET recovered=recovered+?,remaining=remaining-?,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND player_id=?",take,take,row.get("order_id"),playerId);
   transactions.record("REFUND_RECOVERY","PLAYER",playerId,take.negate(),before,wallet.getBalance(),((Number)row.get("order_id")).longValue(),null,null,"新收益抵扣先前退款待追回款");budget=budget.subtract(take);
  }
 }
 private BigDecimal share(BigDecimal income,BigDecimal refund,BigDecimal total){return income.multiply(refund).divide(total,2,RoundingMode.HALF_UP);}
}
