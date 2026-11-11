package com.delta.admin.tenant;

import com.delta.common.security.service.RateLimitService;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.websocket.ChatHandshakeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        classes = WebSocketHandshakeIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.banner-mode=off", "server.address=127.0.0.1", "tenant.enabled=true"})
class WebSocketHandshakeIntegrationTest {
    @LocalServerPort
    int port;

    @Test
    void allowedOriginUpgradesAndCrossTenantOriginGetsForbidden() throws Exception {
        assertThat(status("https://shop-a.example.com")).startsWith("HTTP/1.1 101");
        assertThat(status("https://shop-b.example.com")).startsWith("HTTP/1.1 403");
    }

    private String status(String origin) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream output = socket.getOutputStream();
            String request = "GET /ws/chat HTTP/1.1\r\n"
                    + "Host: shop-a.example.com\r\n"
                    + "Origin: " + origin + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EnableWebSocket
    static class App {
        @Bean
        TenantProperties tenantProperties() {
            TenantProperties properties = new TenantProperties();
            properties.setEnabled(true);
            TenantProperties.Tenant a = new TenantProperties.Tenant();
            a.setHosts(List.of("shop-a.example.com"));
            TenantProperties.Tenant b = new TenantProperties.Tenant();
            b.setHosts(List.of("shop-b.example.com"));
            properties.getTenants().put("tenant-a", a);
            properties.getTenants().put("tenant-b", b);
            return properties;
        }

        @Bean
        TenantRegistry tenantRegistry(TenantProperties properties) {
            return new TenantRegistry(properties);
        }

        @Bean
        ChatHandshakeInterceptor chatHandshakeInterceptor(TenantProperties properties, TenantRegistry registry) {
            return new ChatHandshakeInterceptor(mock(RateLimitService.class), registry, properties);
        }

        @Bean
        WebSocketConfigurer webSocketConfigurer(ChatHandshakeInterceptor interceptor) {
            return registry -> registry.addHandler(new TextWebSocketHandler() {
                        @Override
                        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        }
                    }, "/ws/chat")
                    .addInterceptors(interceptor)
                    .setAllowedOrigins("https://shop-a.example.com", "https://shop-b.example.com");
        }
    }
}
