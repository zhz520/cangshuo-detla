package com.delta.admin.sms;

import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.sms.service.AliyunSmsService;
import com.delta.common.sms.service.SmsGatewayClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class SmsGatewayClientTest {
    @Test
    void selfManagedAliyunModeReadsLegacySettingsAndSendsVerificationCodes() {
        CrossModuleMapper configs = Mockito.mock(CrossModuleMapper.class);
        when(configs.selectConfigValue("sms.provider.mode")).thenReturn("ALIYUN");
        when(configs.selectConfigValue("sms.aliyun.enabled")).thenReturn("true");
        when(configs.selectConfigValue("sms.aliyun.access_key_id")).thenReturn("LTAI-test");
        when(configs.selectConfigValue("sms.aliyun.access_key_secret")).thenReturn("secret-test");
        when(configs.selectConfigValue("sms.aliyun.sign_name")).thenReturn("KK云电竞");
        when(configs.selectConfigValue("sms.aliyun.endpoint")).thenReturn("dysmsapi.aliyuncs.com");
        when(configs.selectConfigValue("sms.aliyun.template_code.verification")).thenReturn("SMS_TEST");
        AliyunSmsService provider = Mockito.mock(AliyunSmsService.class);
        SmsGatewayClient client = new SmsGatewayClient(configs,
                Mockito.mock(com.delta.common.sms.service.InstanceIdentityProvider.class),
                Mockito.mock(com.delta.common.sms.service.GatewayCredentials.class));
        ReflectionTestUtils.setField(client, "legacyProvider", provider);
        assertThat(client.provider()).isEqualTo("ALIYUN");
        assertThat(client.enabled()).isTrue();
        assertThat(client.legacySettings().verificationTemplateCode()).isEqualTo("SMS_TEST");
        client.send("otp_login", "13800138000", Map.of("code", "123456"), "sms-test-key-2");
        verify(provider).sendTemplate(eq("dysmsapi.aliyuncs.com"), eq("LTAI-test"), eq("secret-test"),
                eq("KK云电竞"), eq("SMS_TEST"), eq("13800138000"), anyMap());
    }

    @Test
    void signsTheExactJsonBodyAndAcceptsAValidGatewayResponse() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/sms/v1/send", exchange -> handle(exchange, received));
        server.start();
        try {
            CrossModuleMapper configs = Mockito.mock(CrossModuleMapper.class);
            when(configs.selectConfigValue("sms.gateway.enabled")).thenReturn("true");
            var identity = Mockito.mock(com.delta.common.sms.service.InstanceIdentityProvider.class);
            var creds = Mockito.mock(com.delta.common.sms.service.GatewayCredentials.class);
            String id = "3309776b-7be2-4bfc-a0dd-ea62e3431212";
            when(identity.authorityOrigin()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(identity.currentInstanceId()).thenReturn(id);
            when(creds.secret(id)).thenReturn("sms-test-secret");
            when(configs.selectConfigValue("sms.gateway.timeout-ms")).thenReturn("3000");
            when(configs.selectConfigValue("sms.gateway.client-version")).thenReturn("1.16.5");
            SmsGatewayClient client = new SmsGatewayClient(configs, identity, creds);
            client.send("otp_login", "13800138000", Map.of("code", "123456"), "sms-test-key-1");
            assertThat(received.get()).contains("\"templateKey\":\"otp_login\"", "\"phone\":\"13800138000\"");
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> received) throws java.io.IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String timestamp = exchange.getRequestHeaders().getFirst("x-sms-timestamp");
            String nonce = exchange.getRequestHeaders().getFirst("x-sms-nonce");
            String signature = exchange.getRequestHeaders().getFirst("x-sms-signature");
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            String message = "POST\n/api/sms/v1/send\n" + timestamp + "\n" + nonce + "\n" + exchange.getRequestHeaders().getFirst("idempotency-key") + "\n" + digest;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("sms-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            received.set(body + "\n" + signature + "\n" + expected);
            byte[] response = (signature.equals(expected)
                    ? "{\"status\":\"ACCEPTED\",\"requestId\":\"req-test\"}"
                    : "{\"message\":\"invalid signature\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(signature.equals(expected) ? 200 : 401, response.length);
            exchange.getResponseBody().write(response);
        } catch (Exception error) {
            throw new java.io.IOException(error);
        }
    }
}
