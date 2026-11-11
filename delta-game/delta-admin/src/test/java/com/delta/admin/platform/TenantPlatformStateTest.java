package com.delta.admin.platform;

import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantPlatformStateTest {
    @TempDir Path directory;

    @Test
    void eachTenantGetsItsOwnLicenseIdentityAndStateFile() throws Exception {
        String instanceA=UUID.randomUUID().toString(),instanceB=UUID.randomUUID().toString();
        TenantProperties tenantProperties=new TenantProperties();
        tenantProperties.setEnabled(true);
        tenantProperties.getTenants().put("tenant-a",tenant("shop-a.example.com","customer-a",instanceA));
        tenantProperties.getTenants().put("tenant-b",tenant("shop-b.example.com","customer-b",instanceB));
        TenantRegistry registry=new TenantRegistry(tenantProperties);
        PlatformProperties platformProperties=new PlatformProperties();
        platformProperties.setCustomerId("legacy");
        platformProperties.setStatePath(directory.resolve("state.json").toString());
        platformProperties.setPublicKeyPath(directory.resolve("public.pem").toString());
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        PlatformService service=new PlatformService(platformProperties,json,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service,"tenantProperties",tenantProperties);
        ReflectionTestUtils.setField(service,"tenantRegistry",registry);

        var overviewA=TenantContext.callAs("tenant-a",service::overview);
        var overviewB=TenantContext.callAs("tenant-b",service::overview);

        assertThat(overviewA.customerId()).isEqualTo("customer-a");
        assertThat(overviewB.customerId()).isEqualTo("customer-b");
        assertThat(overviewA.instanceId()).isEqualTo(instanceA);
        assertThat(overviewB.instanceId()).isEqualTo(instanceB);
        assertThat(directory.resolve("tenants").resolve("tenant-a").resolve("state.json")).exists();
        assertThat(directory.resolve("tenants").resolve("tenant-b").resolve("state.json")).exists();
        assertThat(TenantContext.currentIdOrNull()).isNull();
    }

    @Test
    void sharedTenantLicenseUsesTheTenantCustomerIdForReporting() throws Exception {
        String instanceA=UUID.randomUUID().toString();
        TenantProperties tenantProperties=new TenantProperties();
        tenantProperties.setEnabled(true);
        tenantProperties.getTenants().put("tenant-a",tenant("shop-a.example.com","customer-a",instanceA));
        TenantRegistry registry=new TenantRegistry(tenantProperties);
        PlatformProperties platformProperties=new PlatformProperties();
        platformProperties.setCustomerId("legacy");
        platformProperties.setStatePath(directory.resolve("state.json").toString());
        Path publicKey=directory.resolve("public.pem");
        platformProperties.setPublicKeyPath(publicKey.toString());
        var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(publicKey,"-----BEGIN PUBLIC KEY-----\n"
                +Base64.getMimeEncoder(64,"\n".getBytes(StandardCharsets.UTF_8)).encodeToString(keys.getPublic().getEncoded())
                +"\n-----END PUBLIC KEY-----\n");
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        Clock clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
        PlatformService service=new PlatformService(platformProperties,json,clock);
        ReflectionTestUtils.setField(service,"tenantProperties",tenantProperties);
        ReflectionTestUtils.setField(service,"tenantRegistry",registry);
        var now=clock.instant();
        var claims=new PlatformLicense.Claims(UUID.randomUUID().toString(),"customer-a",instanceA,now,now,now.plusSeconds(3600),List.of(),null,null);
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
        Signature signer=Signature.getInstance("Ed25519");signer.initSign(keys.getPrivate());
        signer.update((PlatformLicense.FORMAT+"."+payload).getBytes(StandardCharsets.UTF_8));
        var envelope=new PlatformLicense.Envelope(PlatformLicense.FORMAT,payload,Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));

        var report=TenantContext.callAs("tenant-a",()->{
            service.importLicense(envelope);
            return service.reportPayload();
        });
        assertThat(report).isNotNull();
        assertThat(report.customerId()).isEqualTo("customer-a");
        assertThat(report.instanceId()).isEqualTo(instanceA);
        assertThat(TenantContext.callAs("tenant-a",service::overview).licenseStatus()).isEqualTo("VALID");
    }

    private TenantProperties.Tenant tenant(String host,String customer,String instance){
        TenantProperties.Tenant tenant=new TenantProperties.Tenant();
        tenant.setHosts(List.of(host));
        tenant.setPlatformCustomerId(customer);
        tenant.setPlatformInstanceId(instance);
        return tenant;
    }
}
