package com.delta.admin.pay;

import com.delta.common.config.SecurityConfig;
import com.delta.common.exception.BusinessException;
import com.delta.common.exception.GlobalExceptionHandler;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.security.filter.JwtAuthenticationFilter;
import com.delta.common.security.handler.AccessDeniedHandlerImpl;
import com.delta.common.security.handler.AuthenticationEntryPointImpl;
import com.delta.common.security.service.TokenService;
import com.delta.order.service.OrderService;
import com.delta.pay.config.WxPayConfiguration;
import com.delta.pay.controller.PayController;
import com.delta.pay.entity.Payment;
import com.delta.pay.service.PaymentService;
import com.delta.pay.service.TransactionService;
import com.delta.user.service.UserService;
import com.wechat.pay.java.service.payments.h5.H5Service;
import com.wechat.pay.java.service.payments.h5.model.PrepayRequest;
import com.wechat.pay.java.service.payments.h5.model.PrepayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = DepositH5IntegrationTest.Config.class, properties = {
        "spring.profiles.active=test", "spring.datasource.url=jdbc:h2:mem:deposit-h5;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource"
})
@AutoConfigureMockMvc
class DepositH5IntegrationTest {
    @Configuration
    @EnableAutoConfiguration(excludeName = "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthenticationEntryPointImpl.class,
            AccessDeniedHandlerImpl.class, GlobalExceptionHandler.class, PayController.class})
    static class Config {}

    @Autowired MockMvc mvc;
    @MockBean TokenService tokens;
    @MockBean PaymentService payments;
    @MockBean TransactionService transactions;
    @MockBean OrderService orders;
    @MockBean WxPayConfiguration configuration;
    @MockBean UserService users;
    @MockBean CrossModuleMapper identities;
    H5Service h5;
    WxPayConfiguration.CachedClients clients;

    RequestPostProcessor role(String role, long id) {
        return authentication(new UsernamePasswordAuthenticationToken(Map.of("userId", id, "userType", role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @BeforeEach void setup() {
        clients = mock(WxPayConfiguration.CachedClients.class);
        h5 = mock(H5Service.class);
        when(configuration.getClients()).thenReturn(clients);
        when(configuration.isEnabled()).thenReturn(true);
        when(clients.getConfig()).thenReturn(new WxPayConfiguration.RuntimeConfig(true, "test-app", "test-merchant",
                "https://shop.example/pay/wx/notify", null, null, null, null, null, "WAP", "Test shop"));
        when(clients.getH5Service()).thenReturn(h5);
        Payment payment = new Payment();
        payment.setPaymentNo("deposit-h5-test");
        payment.setUserId(7L);
        payment.setAmount(new BigDecimal("40.25"));
        payment.setBizType("PLAYER_DEPOSIT");
        payment.setStatus("PAYING");
        when(payments.createPlayerDepositPayment(7L)).thenReturn(payment);
        when(identities.selectUserIdByPlayerId(2L)).thenReturn(7L);
        PrepayResponse response = new PrepayResponse();
        response.setH5Url("https://wx.tenpay.com/test-only");
        when(h5.prepay(any(PrepayRequest.class))).thenReturn(response);
    }

    @Test void userGetsH5UrlUsingTheServerCalculatedOutstandingAmountAndExistingCallback() throws Exception {
        mvc.perform(post("/pay/player-deposit/h5").with(role("USER", 7)).contentType("application/json")
                        .content("{\"userId\":8,\"amount\":0.01}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paymentNo").value("deposit-h5-test"))
                .andExpect(jsonPath("$.data.h5Url").value("https://wx.tenpay.com/test-only"))
                .andExpect(jsonPath("$.data.paySign").doesNotExist());
        var captured = ArgumentCaptor.forClass(PrepayRequest.class);
        verify(h5).prepay(captured.capture());
        assertThat(captured.getValue().getAmount().getTotal()).isEqualTo(4025);
        assertThat(captured.getValue().getAmount().getCurrency()).isEqualTo("CNY");
        assertThat(captured.getValue().getOutTradeNo()).isEqualTo("deposit-h5-test");
        assertThat(captured.getValue().getNotifyUrl()).isEqualTo("https://shop.example/pay/wx/notify");
        verify(payments).createPlayerDepositPayment(7L);
        verify(payments, never()).createPlayerDepositPayment(8L);
        verify(clients, never()).getJsapiService();
        verifyNoInteractions(users, transactions);
    }

    @Test void playerTokenPaysOnlyForTheLinkedUser() throws Exception {
        mvc.perform(post("/pay/player-deposit/h5").with(role("PLAYER", 2)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.paymentNo").value("deposit-h5-test"));
        verify(identities).selectUserIdByPlayerId(2L);
        verify(payments).createPlayerDepositPayment(7L);
        verify(payments, never()).createPlayerDepositPayment(2L);
    }

    @Test void anonymousAndStaffCannotInitiatePlayerDeposits() throws Exception {
        mvc.perform(post("/pay/player-deposit/h5")).andExpect(jsonPath("$.code").value(401));
        for (String type : List.of("ADMIN", "CS"))
            mvc.perform(post("/pay/player-deposit/h5").with(role(type, 1))).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(payments, h5);
    }

    @Test void disabledWechatPaymentDoesNotCreateADeposit() throws Exception {
        when(configuration.isEnabled()).thenReturn(false);
        mvc.perform(post("/pay/player-deposit/h5").with(role("USER", 7))).andExpect(jsonPath("$.code").value(500));
        verifyNoInteractions(payments, h5);
    }

    @Test void missingLinkedUserCannotChargeAnUnrelatedUser() throws Exception {
        when(identities.selectUserIdByPlayerId(2L)).thenReturn(null);
        mvc.perform(post("/pay/player-deposit/h5").with(role("PLAYER", 2))).andExpect(jsonPath("$.code").value(500));
        verifyNoInteractions(payments, h5);
    }

    @Test void existingDepositPolicyRejectionsDoNotCallThePaymentProvider() throws Exception {
        when(payments.createPlayerDepositPayment(7L)).thenThrow(new BusinessException(409, "已足额缴纳押金"));
        mvc.perform(post("/pay/player-deposit/h5").with(role("USER", 7))).andExpect(jsonPath("$.code").value(409));
        verifyNoInteractions(h5);
    }

    @Test void providerFailureDoesNotMarkTheDepositPaid() throws Exception {
        when(h5.prepay(any(PrepayRequest.class))).thenThrow(new IllegalStateException("test-provider-failure"));
        mvc.perform(post("/pay/player-deposit/h5").with(role("USER", 7))).andExpect(jsonPath("$.code").value(500));
        verify(payments, never()).updateById(any(Payment.class));
        verifyNoInteractions(transactions);
    }
}
