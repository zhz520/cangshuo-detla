package com.delta.admin.platform;

import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private TenantProperties.Tenant tenant(String host,String customer,String instance){
        TenantProperties.Tenant tenant=new TenantProperties.Tenant();
        tenant.setHosts(List.of(host));
        tenant.setPlatformCustomerId(customer);
        tenant.setPlatformInstanceId(instance);
        return tenant;
    }
}
