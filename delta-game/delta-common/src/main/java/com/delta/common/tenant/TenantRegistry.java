package com.delta.common.tenant;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable, validated view of the configured tenant and domain registry. */
@Component
public class TenantRegistry {
    private final boolean sharedEnabled;
    private final Map<String, TenantDefinition> byId;
    private final Map<String, TenantDefinition> byHost;

    public TenantRegistry(TenantProperties properties) {
        this.sharedEnabled = properties.isEnabled();
        Map<String, TenantDefinition> definitions = new LinkedHashMap<>();
        Map<String, TenantDefinition> hosts = new LinkedHashMap<>();
        for (Map.Entry<String, TenantProperties.Tenant> entry : properties.getTenants().entrySet()) {
            TenantDefinition definition = new TenantDefinition(entry.getKey(), entry.getValue());
            if (sharedEnabled && definition.active() && definition.hosts().isEmpty()) {
                throw new IllegalStateException("启用中的租户未配置域名: " + definition.id());
            }
            definitions.put(definition.id(), definition);
            for (String host : definition.hosts()) {
                TenantDefinition previous = hosts.putIfAbsent(host, definition);
                if (previous != null) {
                    throw new IllegalStateException("域名被多个租户重复登记: " + host);
                }
            }
        }
        if (sharedEnabled && definitions.values().stream().noneMatch(TenantDefinition::active)) {
            throw new IllegalStateException("共享租户模式已启用，但没有可用租户");
        }
        this.byId = Map.copyOf(definitions);
        this.byHost = Map.copyOf(hosts);
    }

    public boolean isSharedEnabled() { return sharedEnabled; }

    public Optional<TenantDefinition> findByHost(String host) {
        try {
            return Optional.ofNullable(byHost.get(TenantHosts.normalize(host)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public Optional<TenantDefinition> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public List<TenantDefinition> activeTenants() {
        return byId.values().stream().filter(TenantDefinition::active).toList();
    }

    public Collection<TenantDefinition> allTenants() {
        return byId.values();
    }

    public Set<String> registeredHosts() {
        return Set.copyOf(new LinkedHashSet<>(byHost.keySet()));
    }
}
