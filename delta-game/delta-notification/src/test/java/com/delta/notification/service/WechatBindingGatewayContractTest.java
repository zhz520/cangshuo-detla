package com.delta.notification.service;

import com.delta.common.exception.BusinessException;
import com.delta.common.sms.service.SmsGatewayClient;
import com.delta.platform.PlatformLicense;
import com.delta.platform.PlatformService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatBindingGatewayContractTest {
    @Test void clientUsesSignedBindingEndpointsAndRejectsAnOpenidFromAnotherApp() throws Exception {
        String appId="wx1111111111111111", component="wx2222222222222222";
        var json=new ObjectMapper();
        var platform=mock(PlatformService.class);
        var gateway=mock(SmsGatewayClient.class);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var bodies=new ConcurrentHashMap<String,JsonNode>();
        var replyApp=new AtomicReference<>(appId);
        server.createContext("/api/client/wechat-open/",exchange->{
            try(exchange){
                String path=exchange.getRequestURI().getPath();
                if(!"POST".equals(exchange.getRequestMethod()) || !"fixture".equals(exchange.getRequestHeaders().getFirst("X-Test-Signature"))){
                    exchange.sendResponseHeaders(403,-1);return;
                }
                bodies.put(path,json.readTree(exchange.getRequestBody()));
                var reply=path.endsWith("/binding-component")?json.createObjectNode().put("componentAppId",component):
                        json.createObjectNode().put("appId",replyApp.get()).put("openid","verified-follower-1");
                byte[] bytes=json.writeValueAsBytes(reply);
                exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try{
            when(platform.authorityOrigin()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(platform.reportPayload()).thenReturn(new PlatformService.ReportPayload("test","test","test",
                    new PlatformLicense.Envelope("format","payload","fixture-signature")));
            when(gateway.signedJsonRequest(any(URI.class),anyString())).thenAnswer(inv->HttpRequest.newBuilder((URI)inv.getArgument(0))
                    .header("X-Test-Signature","fixture").POST(HttpRequest.BodyPublishers.ofString(inv.getArgument(1),StandardCharsets.UTF_8)));
            var client=new WechatOpenPlatformClient(platform,gateway,null,json);
            assertThat(client.bindingComponent(appId)).isEqualTo(component);
            assertThat(client.bindingOpenid(appId,"one-code")).isEqualTo("verified-follower-1");
            var start=bodies.get("/api/client/wechat-open/binding-component");
            assertThat(start.size()).isEqualTo(2);assertThat(start.path("appId").asText()).isEqualTo(appId);
            assertThat(start.path("license").path("payload").asText()).isEqualTo("payload");
            var finish=bodies.get("/api/client/wechat-open/binding-openid");
            assertThat(finish.size()).isEqualTo(3);assertThat(finish.path("code").asText()).isEqualTo("one-code");
            assertThat(finish.path("appId").asText()).isEqualTo(appId);
            replyApp.set("wx3333333333333333");
            assertThatThrownBy(()->client.bindingOpenid(appId,"another-code")).isInstanceOf(BusinessException.class);
        }finally{server.stop(0);}
    }
}
