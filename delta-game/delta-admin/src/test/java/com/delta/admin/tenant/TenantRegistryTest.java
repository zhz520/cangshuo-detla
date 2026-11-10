package com.delta.admin.tenant;

import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRegistryTest {
    @TempDir Path directory;

    @Test
    void resolvesOnlyExplicitlyRegisteredNormalizedHosts() {
        TenantRegistry registry = new TenantRegistry(properties());

        assertThat(registry.findByHost("SHOP-A.EXAMPLE.COM.")).get().extracting(t -> t.id()).isEqualTo("tenant-a");
        assertThat(registry.findByHost("shop-b.example.com")).get().extracting(t -> t.id()).isEqualTo("tenant-b");
        assertThat(registry.findByHost("attacker.example.com")).isEmpty();
        assertThat(registry.findByHost("shop-a.example.com:443")).isEmpty();
        assertThat(registry.findByHost("shop-a.example.com/path")).isEmpty();
    }

    @Test
    void rejectsDuplicateDomainOwnership() {
        TenantProperties properties = properties();
        properties.getTenants().get("tenant-b").setHosts(List.of("shop-a.example.com"));

        assertThatThrownBy(() -> new TenantRegistry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复登记");
    }

    @Test
    void keepsInactiveTenantAddressableButOutOfActiveWork() {
        TenantProperties properties = properties();
        properties.getTenants().get("tenant-b").setActive(false);
        TenantRegistry registry = new TenantRegistry(properties);

        assertThat(registry.findByHost("shop-b.example.com")).isPresent();
        assertThat(registry.activeTenants()).extracting(t -> t.id()).containsExactly("tenant-a");
    }

    @Test
    void requiresAtLeastOneActiveTenantWhenSharedModeIsOn() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> new TenantRegistry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可用租户");
    }

    @Test
    void databasePasswordIsNotExposedByDefinitionToString() {
        TenantProperties properties = properties();
        properties.getTenants().get("tenant-a").setJdbcUrl("jdbc:mysql://db/a");
        properties.getTenants().get("tenant-a").setUsername("tenant_a");
        properties.getTenants().get("tenant-a").setPassword("never-print-this");

        String rendered = new TenantRegistry(properties).findById("tenant-a").orElseThrow().toString();

        assertThat(rendered).doesNotContain("never-print-this");
        assertThat(rendered).contains("tenant-a");
    }

    @Test
    void canLoadTheRegistryFromTheProtectedControlPlaneFile() throws Exception {
        Path file = directory.resolve("tenants.json");
        Files.writeString(file, """
                {"tenants":{"tenant-a":{"hosts":["shop-a.example.com"],
                  "jdbcUrl":"jdbc:h2:mem:registry","username":"sa","driverClassName":"org.h2.Driver"}}}
                """);
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        properties.setRegistryFile(file.toString());

        TenantRegistry registry = new TenantRegistry(properties, new ObjectMapper());

        assertThat(registry.findByHost("shop-a.example.com")).get().extracting(t -> t.id()).isEqualTo("tenant-a");
        assertThat(registry.isSharedEnabled()).isTrue();
    }

    private TenantProperties properties() {
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
}
