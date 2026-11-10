package com.delta.common.tenant;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.Files;
import java.nio.file.Path;

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
        this(properties, new ObjectMapper());
    }

    @Autowired
    public TenantRegistry(TenantProperties properties, ObjectMapper json) {
        TenantProperties loaded = loadRegistry(properties, json);
        this.sharedEnabled = loaded.isEnabled();
        Map<String, TenantDefinition> definitions = new LinkedHashMap<>();
        Map<String, TenantDefinition> hosts = new LinkedHashMap<>();
        for (Map.Entry<String, TenantProperties.Tenant> entry : loaded.getTenants().entrySet()) {
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

    private static TenantProperties loadRegistry(TenantProperties properties, ObjectMapper json) {
        String file = properties.getRegistryFile() == null ? "" : properties.getRegistryFile().trim();
        if (file.isEmpty()) return properties;
        try {
            Path path = Path.of(file).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || Files.size(path) > 2_000_000) {
                throw new IllegalStateException("租户注册文件不可用");
            }
            try {
                boolean exposed=Files.getPosixFilePermissions(path).stream()
                        .anyMatch(permission->permission.name().startsWith("OTHERS_"));
                if(exposed)throw new IllegalStateException("租户注册文件权限过宽，必须设为仅运维账号可读");
            }catch(UnsupportedOperationException ignored){/* Windows or non-POSIX storage. */}
            JsonNode root = json.readTree(path.toFile());
            if (root == null || !root.isObject()) throw new IllegalStateException("租户注册文件格式无效");
            JsonNode envelope = root.has("tenants") ? root
                    : json.createObjectNode().set("tenants", root);
            TenantProperties loaded = json.treeToValue(envelope, TenantProperties.class);
            loaded.setEnabled(properties.isEnabled());
            loaded.setRegistryFile(file);
            if (loaded.getTenants() == null || loaded.getTenants().isEmpty()) {
                throw new IllegalStateException("租户注册文件没有客户");
            }
            return loaded;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("租户注册文件读取失败", error);
        }
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
