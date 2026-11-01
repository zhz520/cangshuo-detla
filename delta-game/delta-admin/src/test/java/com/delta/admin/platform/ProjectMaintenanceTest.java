package com.delta.admin.platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProjectMaintenanceTest {
    @TempDir Path directory;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    Instant now=Instant.parse("2026-11-01T00:00:00Z");
    KeyPair keys;PlatformProperties properties;PlatformService platform;
    @BeforeEach void setup() throws Exception {
        keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var key=directory.resolve("license-public.pem");Files.writeString(key,"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        properties=new PlatformProperties();properties.setCustomerId("742");properties.setStatePath(directory.resolve("state.json").toString());properties.setPublicKeyPath(key.toString());properties.setReportUrl("https://authority.example/api/client/report");
        platform=new PlatformService(properties,json,Clock.fixed(now,ZoneOffset.UTC));
    }
    PlatformRemote remote(Instant time){return new PlatformRemote(properties,platform,json,Clock.fixed(time,ZoneOffset.UTC));}
    void credential(String policy,String instance)throws Exception {
        var claims=Map.of("customerId","742","instanceId",instance,"issuedAt",now.toString(),"maintenanceUntil",now.plusSeconds(60).toString(),"status","ACTIVE","pausePolicy",policy,"allowedDomains",List.of("shop.example.com"));
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
        var signer=Signature.getInstance("Ed25519");signer.initSign(keys.getPrivate());signer.update(("cangshuo-project-v1."+payload).getBytes(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve("authority.json"),json.writeValueAsString(Map.of("project",Map.of("format","cangshuo-project-v1","payload",payload,"signature",Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())))));
    }
    @Test void expiryBlocksWithoutNeedingNetworkAndRenewalRestores()throws Exception {
        credential("STOP_ALL",platform.overview().instanceId());assertThat(remote(now).suspended()).isFalse();assertThat(remote(now.plusSeconds(60)).suspended()).isTrue();
        assertThat(remote(now.plusSeconds(59)).suspended()).isFalse();
    }
    @Test void forgedOrCrossInstanceProjectCannotGrantBusinessAccess()throws Exception {
        credential("STOP_ALL",UUID.randomUUID().toString());assertThat(remote(now).suspended()).isTrue();
        credential("STOP_ALL",platform.overview().instanceId());var file=directory.resolve("authority.json");Files.writeString(file,Files.readString(file).replace("STOP_ALL","PENDING")+"bad");assertThat(remote(now).suspended()).isTrue();
    }
    @Test void pendingPolicyDoesNotActivateAnUnconfirmedSuspensionRule()throws Exception {
        credential("PENDING",platform.overview().instanceId());assertThat(remote(now.plusSeconds(90)).suspended()).isFalse();
    }
    @Test void suspensionBlocksBusinessButPreservesRenewalLoginAndPaymentCallbacks()throws Exception {
        credential("STOP_ALL",platform.overview().instanceId());var filter=new ProjectMaintenanceFilter(remote(now.plusSeconds(60)),json);
        var request=new MockHttpServletRequest("POST","/order");request.setServletPath("/order");var response=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(request,response,chain);assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).contains("maintenance");assertThat(chain.getRequest()).isNull();
        for(String path:List.of("/admin/platform/updates/check","/user/auth/login","/pay/wx/notify","/system/maintenance/status")){
            request=new MockHttpServletRequest("POST",path);request.setServletPath(path);response=new MockHttpServletResponse();chain=new MockFilterChain();filter.doFilter(request,response,chain);assertThat(chain.getRequest()).isNotNull();
        }
    }
}
