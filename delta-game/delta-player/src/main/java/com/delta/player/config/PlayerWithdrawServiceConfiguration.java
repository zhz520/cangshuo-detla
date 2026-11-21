package com.delta.player.config;

import com.delta.pay.service.PaymentService;
import com.delta.pay.service.TransactionService;
import com.delta.player.service.PlayerAccountService;
import com.delta.player.service.PlayerIdentityNameService;
import com.delta.player.service.PlayerService;
import com.delta.player.service.PlayerWalletService;
import com.delta.player.service.PlayerWithdrawService;
import com.delta.player.service.WithdrawService;
import com.delta.system.service.SysConfigService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit wiring for the withdrawal application service, including controller slice tests. */
@Configuration(proxyBeanMethods = false)
public class PlayerWithdrawServiceConfiguration {
    @Bean
    public PlayerWithdrawService playerWithdrawService(WithdrawService withdrawService, JdbcTemplate jdbc,
                                                       PlayerService playerService, PlayerWalletService playerWalletService,
                                                       TransactionService transactionService, ApplicationEventPublisher eventPublisher,
                                                       SysConfigService sysConfigService, PlayerAccountService accounts,
                                                       PaymentService paymentService, PlayerIdentityNameService identityNames) {
        return new PlayerWithdrawService(withdrawService, jdbc, playerService, playerWalletService, transactionService,
                eventPublisher, sysConfigService, accounts, paymentService, identityNames);
    }
}
