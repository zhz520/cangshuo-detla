package com.delta.admin;
import com.delta.admin.service.SettlementRecoveryService;
import com.delta.common.event.BusinessEvent;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerWalletService;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SettlementRecoveryTest {
 JdbcTemplate jdbc;PlayerWalletService wallets;TransactionService transactions;SettlementRecoveryService service;
 Map<Long,PlayerWallet> balances;
 @BeforeEach void setup()throws Exception{
  var ds=new DriverManagerDataSource("jdbc:h2:mem:recovery"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);
  try(var conn=ds.getConnection()){ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/governance-v1.sql"));}
  jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,amount DECIMAL(16,2),settled INT)");
  jdbc.execute("CREATE TABLE payment(order_id BIGINT,status VARCHAR(32),refund_amount DECIMAL(16,2))");
  jdbc.execute("CREATE TABLE `transaction`(user_id BIGINT,user_type VARCHAR(16),type VARCHAR(32),related_order_id BIGINT,amount DECIMAL(16,2))");
  wallets=mock(PlayerWalletService.class);transactions=mock(TransactionService.class);balances=new HashMap<>();
  when(wallets.getByPlayerId(anyLong())).thenAnswer(a->balances.get(a.getArgument(0)));
  service=new SettlementRecoveryService(jdbc,wallets,transactions);
 }
 void wallet(long id,String balance){var w=new PlayerWallet();w.setPlayerId(id);w.setBalance(new BigDecimal(balance));balances.put(id,w);}
 BusinessEvent refunded(){return new BusinessEvent(this,"ORDER_REFUNDED","USER",7L,100L,"退款已确认");}
 @Test void refundReversesEachParticipantsShareAndRetainsDebtIdempotently(){
  jdbc.update("INSERT INTO `order` VALUES (100,100,1)");jdbc.update("INSERT INTO payment VALUES (100,'REFUNDED',50)");
  jdbc.update("INSERT INTO `transaction` VALUES (1,'PLAYER','INCOME',100,50),(2,'PLAYER','INCOME',100,30)");
  jdbc.update("INSERT INTO order_settlement_ledger(order_id,amount,club_amount,player_amount,cs_amount) VALUES (100,100,20,80,0)");wallet(1,"10");wallet(2,"30");
  service.refunded(refunded());service.refunded(refunded());
  assertThat(balances.get(1L).getBalance()).isEqualByComparingTo("0");assertThat(balances.get(2L).getBalance()).isEqualByComparingTo("15");
  assertThat(jdbc.queryForObject("SELECT remaining FROM player_refund_recovery WHERE player_id=1",BigDecimal.class)).isEqualByComparingTo("15");
  assertThat(jdbc.queryForObject("SELECT player_refund FROM order_settlement_refund WHERE order_id=100",BigDecimal.class)).isEqualByComparingTo("40");
  assertThat(jdbc.queryForObject("SELECT club_refund FROM order_settlement_refund WHERE order_id=100",BigDecimal.class)).isEqualByComparingTo("10");
  verify(transactions,times(2)).record(eq("REFUND"),eq("PLAYER"),anyLong(),any(),any(),any(),eq(100L),isNull(),isNull(),anyString());
  balances.get(1L).setBalance(new BigDecimal("20"));service.recover(1L,new BigDecimal("20"));
  assertThat(balances.get(1L).getBalance()).isEqualByComparingTo("5");assertThat(jdbc.queryForObject("SELECT remaining FROM player_refund_recovery WHERE player_id=1",BigDecimal.class)).isEqualByComparingTo("0");
 }
 @Test void roundingNeverCreatesExtraRefundMoneyAcrossTeammates(){
  jdbc.update("INSERT INTO `order` VALUES (100,0.04,1)");jdbc.update("INSERT INTO payment VALUES (100,'REFUNDED',0.01)");
  for(long id=1;id<=4;id++){wallet(id,"1");jdbc.update("INSERT INTO `transaction` VALUES (?,'PLAYER','INCOME',100,0.01)",id);}
  service.refunded(refunded());
  assertThat(jdbc.queryForObject("SELECT SUM(refund_share) FROM player_refund_recovery",BigDecimal.class)).isEqualByComparingTo("0.01");
 }
 @Test void unfinishedWechatRefundNeverDeductsPlayerIncome(){
  jdbc.update("INSERT INTO `order` VALUES (100,100,1)");jdbc.update("INSERT INTO payment VALUES (100,'REFUNDING',50)");
  jdbc.update("INSERT INTO `transaction` VALUES (1,'PLAYER','INCOME',100,80)");wallet(1,"80");service.refunded(refunded());
  assertThat(balances.get(1L).getBalance()).isEqualByComparingTo("80");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_refund_recovery",Integer.class)).isZero();
 }
}
