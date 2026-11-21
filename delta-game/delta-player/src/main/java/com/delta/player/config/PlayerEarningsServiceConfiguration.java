package com.delta.player.config;

import com.delta.order.service.OrderService;
import com.delta.pay.service.TransactionService;
import com.delta.player.service.PlayerEarningsService;
import com.delta.player.service.PlayerWalletService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit wiring for the earnings query service, including controller slice tests. */
@Configuration(proxyBeanMethods = false)
public class PlayerEarningsServiceConfiguration {
    @Bean
    public PlayerEarningsService playerEarningsService(TransactionService transactionService, JdbcTemplate jdbc,
                                                       PlayerWalletService playerWalletService, OrderService orderService) {
        return new PlayerEarningsService(transactionService, jdbc, playerWalletService, orderService);
    }
}
