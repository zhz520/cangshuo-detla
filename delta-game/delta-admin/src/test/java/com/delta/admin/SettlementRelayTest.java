package com.delta.admin;

import com.delta.admin.listener.SettlementEventListener;
import com.delta.finance.service.SettlementRecoveryService;
import com.delta.common.event.OrderConfirmedEvent;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderService;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerWalletService;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementRelayTest {
    private final Map<Long,PlayerWallet> wallets=new HashMap<>();
    private SettlementEventListener listener;

    @BeforeEach
    void setup() throws Exception {
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:settlement_relay_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        try(var connection=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/governance-v1.sql"));
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS player_income_settlement(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,player_id BIGINT,role VARCHAR(20),amount DECIMAL(12,2),reversed_amount DECIMAL(12,2) DEFAULT 0,status VARCHAR(20),release_at TIMESTAMP,released_at TIMESTAMP,attempts INT DEFAULT 0,last_error VARCHAR(255),next_attempt_at TIMESTAMP,remark VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS refund_request(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32))");
        OrderService orders=mock(OrderService.class);
        SettlementRecoveryService recovery=mock(SettlementRecoveryService.class);
        OrderPlayerService participants=mock(OrderPlayerService.class);
        PlayerWalletService playerWallets=mock(PlayerWalletService.class);
        TransactionService transactions=mock(TransactionService.class);
        SysConfigService configs=mock(SysConfigService.class);

        Order order=new Order();order.setId(100L);order.setUserId(7L);order.setPlayerId(3L);order.setStatus("CONFIRMED");
        order.setAmount(new BigDecimal("100.00"));order.setCommissionRate(new BigDecimal("0.20"));order.setSettled(0);
        when(orders.getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(order);

        OrderPlayer teammate=new OrderPlayer();teammate.setPlayerId(2L);teammate.setRole("TEAMMATE");teammate.setStatus("ACCEPTED");teammate.setSplitType("FIFTY_FIFTY");
        OrderPlayer relayOut=new OrderPlayer();relayOut.setPlayerId(1L);relayOut.setRole("RELAY_OUT");relayOut.setStatus("ACCEPTED");relayOut.setSplitType("CUSTOM");relayOut.setSplitAmount(new BigDecimal("10.00"));
        OrderPlayer primary=new OrderPlayer();primary.setPlayerId(3L);primary.setRole("PRIMARY");primary.setStatus("ACCEPTED");
        when(participants.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(teammate),List.of(relayOut),List.of(primary));
        when(playerWallets.getByPlayerId(anyLong())).thenAnswer(invocation->{
            long id=invocation.getArgument(0);return wallets.computeIfAbsent(id,key->{var wallet=new PlayerWallet();wallet.setPlayerId(key);wallet.setBalance(BigDecimal.ZERO);wallet.setTotalIncome(BigDecimal.ZERO);return wallet;});
        });
        when(configs.getConfigValue(eq("settlement.hold_hours"),anyString())).thenReturn("72");
        listener=new SettlementEventListener(orders,jdbc,participants,playerWallets,configs);
    }

    @Test
    void relayAndTeammateSharesAreDeductedFromPrimaryWithoutCreatingMoney() {
        listener.onOrderConfirmed(new OrderConfirmedEvent(this,100L,"SYSTEM"));

        assertThat(wallets.values()).allMatch(wallet -> wallet.getBalance().signum() == 0);
        assertThat(wallets.get(1L).getPendingIncome()).isEqualByComparingTo("10.00");
        assertThat(wallets.get(2L).getPendingIncome()).isEqualByComparingTo("26.67");
        assertThat(wallets.get(3L).getPendingIncome()).isEqualByComparingTo("43.33");
        assertThat(wallets.values().stream().map(PlayerWallet::getPendingIncome).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo("80.00");
    }
}
