package com.delta.identity.service;

import com.delta.common.exception.BusinessException;
import com.delta.common.sms.service.SmsGatewayClient;
import com.delta.platform.PlatformLicense;
import com.delta.platform.PlatformProperties;
import com.delta.platform.PlatformService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityBillingClientTest {
    private static final String HOST = "127.0.0.1";
    private static final String REPORT_PATH = "/api/client/report";
    private static final String VERSION = "1.0.0-synthetic";
    private static final String PACKAGE_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final PlatformLicense.Envelope LICENSE = new PlatformLicense.Envelope(
            "delta-license-v1", "synthetic-license-payload", "synthetic-signature");

    private final Map<String, StubResponse> responses = new ConcurrentHashMap<>();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private HttpServer server;
    private int port;
    private PlatformProperties properties;
    private PlatformService platform;
    private SmsGatewayClient gateway;
    private ObjectMapper json;
    private IdentityBillingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();

        properties = mock(PlatformProperties.class);
        when(properties.getReportUrl()).thenReturn("http://" + HOST + ":" + port + REPORT_PATH);
        platform = mock(PlatformService.class);
        when(platform.reportPayload()).thenReturn(new PlatformService.ReportPayload(
                "synthetic-customer", "synthetic-instance", VERSION, LICENSE));
        when(platform.overview()).thenReturn(new PlatformService.Overview(
                "synthetic-customer", "synthetic-instance", VERSION, "synthetic", true,
                "ACTIVE", "synthetic-license", null, "MANUAL", "MANUAL", List.of(), false));
        gateway = mock(SmsGatewayClient.class);
        when(gateway.signedJsonRequest(any(URI.class), anyString())).thenAnswer(invocation -> {
            URI endpoint = invocation.getArgument(0);
            String body = invocation.getArgument(1);
            return HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        });
        json = new ObjectMapper();
        client = new IdentityBillingClient(properties, platform, json, gateway);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        captured.set(new CapturedRequest(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        StubResponse response = responses.get(exchange.getRequestURI().getPath());
        if (response == null) {
            respond(exchange, 404, "{\"message\":\"unexpected synthetic path\"}");
            return;
        }
        respond(exchange, response.status(), response.body());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    @Test
    void verifyPostsTheIdentityContractToTheDedicatedEndpoint() throws Exception {
        responses.put("/api/client/identity/verify", new StubResponse(200,
                "{\"matched\":true,\"status\":\"CHARGED\",\"balanceCents\":481,\"ignored\":\"synthetic\"}"));

        IdentityBillingClient.Result result = client.verify(
                "SYNTHETIC-NAME", "SYNTHETIC-ID-0001", "synthetic-idempotency-001");

        assertThat(result.matched()).isTrue();
        assertThat(result.status()).isEqualTo("CHARGED");
        assertThat(result.balanceCents()).isEqualTo(481L);

        CapturedRequest request = captured.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/client/identity/verify");
        assertThat(request.contentType()).isEqualTo("application/json");
        JsonNode body = json.readTree(request.body());
        assertThat(body.path("license").path("format").asText()).isEqualTo("delta-license-v1");
        assertThat(body.path("license").path("payload").asText()).isEqualTo("synthetic-license-payload");
        assertThat(body.path("license").path("signature").asText()).isEqualTo("synthetic-signature");
        assertThat(body.path("version").asText()).isEqualTo(VERSION);
        assertThat(body.path("idempotencyKey").asText()).isEqualTo("synthetic-idempotency-001");
        assertThat(body.path("realName").asText()).isEqualTo("SYNTHETIC-NAME");
        assertThat(body.path("idNumber").asText()).isEqualTo("SYNTHETIC-ID-0001");
    }

    @Test
    void accountUsesTheDedicatedEndpointAndReturnsThePayload() throws Exception {
        responses.put("/api/client/identity/account", new StubResponse(200,
                "{\"balanceCents\":900,\"packages\":[{\"id\":\"synthetic-package\"}]}"));

        JsonNode account = client.account();

        assertThat(account.path("balanceCents").asLong()).isEqualTo(900L);
        assertThat(account.path("packages").get(0).path("id").asText()).isEqualTo("synthetic-package");
        CapturedRequest request = captured.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/client/identity/account");
        JsonNode body = json.readTree(request.body());
        assertThat(body.path("license").path("format").asText()).isEqualTo("delta-license-v1");
        assertThat(body.path("version").asText()).isEqualTo(VERSION);
        assertThat(body.has("idempotencyKey")).isFalse();
        assertThat(body.has("realName")).isFalse();
        assertThat(body.has("idNumber")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"409,409", "429,429", "403,403", "400,502", "401,502", "500,502"})
    void mapsAuthorityErrorsToSafeBusinessCodes(int upstreamStatus, int expectedCode) {
        responses.put("/api/client/identity/account",
                new StubResponse(upstreamStatus, "{\"message\":\"synthetic upstream limit\"}"));

        assertThatThrownBy(client::account)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(expectedCode);
                    assertThat(error.getMessage()).isEqualTo("synthetic upstream limit");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void checkoutUsesThePackageEndpointAndAcceptsTheSameOriginPaymentUrl() throws Exception {
        String checkoutUrl = "http://" + HOST + ":" + port + "/pay/identity-package/" + "A".repeat(43);
        responses.put("/api/client/identity/packages/" + PACKAGE_ID + "/checkout",
                new StubResponse(200, json.writeValueAsString(Map.of(
                        "checkoutUrl", checkoutUrl, "orderId", "synthetic-order"))));

        JsonNode result = client.checkout(PACKAGE_ID);

        assertThat(result.path("checkoutUrl").asText()).isEqualTo(checkoutUrl);
        CapturedRequest request = captured.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(
                "/api/client/identity/packages/" + PACKAGE_ID + "/checkout");
        JsonNode body = json.readTree(request.body());
        assertThat(body.path("license").path("format").asText()).isEqualTo("delta-license-v1");
        assertThat(body.path("version").asText()).isEqualTo(VERSION);
    }

    @Test
    void rejectsCrossOriginCheckoutUrls() throws Exception {
        String foreign = "https://authority.example.invalid/pay/identity-package/" + "A".repeat(43);
        responses.put("/api/client/identity/packages/" + PACKAGE_ID + "/checkout",
                new StubResponse(200, json.writeValueAsString(Map.of("checkoutUrl", foreign))));

        assertThatThrownBy(() -> client.checkout(PACKAGE_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("实名套餐支付地址无效");
                });
    }

    @Test
    void rejectsCheckoutUrlsWithAbnormalPaths() throws Exception {
        String endpointPath = "/api/client/identity/packages/" + PACKAGE_ID + "/checkout";
        String origin = "http://" + HOST + ":" + port;
        List<String> abnormalPaths = List.of(
                "/pay/identity-package/" + "A".repeat(42),
                "/pay/identity-package/" + "A".repeat(44),
                "/pay/identity-package/" + "A".repeat(42) + "!",
                "/pay/sms-package/" + "A".repeat(43));

        for (String abnormalPath : abnormalPaths) {
            responses.put(endpointPath, new StubResponse(200,
                    json.writeValueAsString(Map.of("checkoutUrl", origin + abnormalPath))));
            assertThatThrownBy(() -> client.checkout(PACKAGE_ID))
                    .as("checkout path %s", abnormalPath)
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.getCode()).isEqualTo(502);
                        assertThat(error.getMessage()).isEqualTo("实名套餐支付地址无效");
                    });
        }
    }

    @Test
    void mapsNonJsonResponsesTo502WithoutLeakingTheBody() {
        responses.put("/api/client/identity/verify",
                new StubResponse(200, "<html>synthetic-internal-detail</html>"));

        assertThatThrownBy(() -> client.verify("SYNTHETIC-NAME", "SYNTHETIC-ID-0001",
                "synthetic-idempotency-001"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("实名核验服务返回异常");
                    assertThat(error.getMessage()).doesNotContain("synthetic-internal-detail", "<html>");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void mapsConnectionFailuresTo502WithoutLeakingInternalDetails() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            closedPort = socket.getLocalPort();
        }
        when(properties.getReportUrl()).thenReturn("http://" + HOST + ":" + closedPort + REPORT_PATH);

        assertThatThrownBy(() -> client.account())
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("实名核验服务连接失败，请稍后重试");
                    assertThat(error.getMessage()).doesNotContain(
                            String.valueOf(closedPort), "ConnectException", "refused");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void mapsGatewayFailuresTo502WithoutLeakingTheCause() {
        when(gateway.signedJsonRequest(any(URI.class), anyString()))
                .thenThrow(new IllegalStateException("synthetic-internal-signing-detail"));

        assertThatThrownBy(() -> client.account())
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("实名核验服务连接失败，请稍后重试");
                    assertThat(error.getMessage()).doesNotContain("synthetic-internal-signing-detail");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void mapsInterruptedRequestsTo503WithoutLeakingInternalDetails() {
        responses.put("/api/client/identity/account", new StubResponse(200, "{\"balanceCents\":900}"));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.account())
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.getCode()).isEqualTo(503);
                        assertThat(error.getMessage()).isEqualTo("实名核验服务请求已中断");
                        assertThat(error.getMessage()).doesNotContain("InterruptedException", HOST);
                        assertThat(error.getCause()).isNull();
                    });
        } finally {
            Thread.interrupted();
        }
    }

    private record StubResponse(int status, String body) {}

    private record CapturedRequest(String method, String path, String contentType, String body) {}
}
