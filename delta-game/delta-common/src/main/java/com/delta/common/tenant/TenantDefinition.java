package com.delta.common.tenant;

import java.util.List;

/** Immutable tenant routing metadata. Credentials are intentionally omitted from toString(). */
public final class TenantDefinition {
    private final String id;
    private final boolean active;
    private final List<String> hosts;
    private final String storageRoot;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String driverClassName;
    private final String platformCustomerId;
    private final String platformInstanceId;
    private final boolean miniappEnabled;
    private final String miniappAppId;
    private final String miniappSecret;
    private final int maximumPoolSize;
    private final int minimumIdle;
    private final long connectionTimeoutMs;

    TenantDefinition(String id, TenantProperties.Tenant source) {
        this.id = TenantIds.requireValid(id);
        this.active = source.isActive();
        this.hosts = source.getHosts().stream().map(TenantHosts::normalize).distinct().toList();
        this.storageRoot = source.getStorageRoot() == null ? "" : source.getStorageRoot().trim();
        this.jdbcUrl = source.getJdbcUrl() == null ? "" : source.getJdbcUrl().trim();
        this.username = source.getUsername() == null ? "" : source.getUsername().trim();
        this.password = source.getPassword() == null ? "" : source.getPassword();
        this.driverClassName = source.getDriverClassName() == null ? "" : source.getDriverClassName().trim();
        this.platformCustomerId = source.getPlatformCustomerId() == null ? "" : source.getPlatformCustomerId().trim();
        this.platformInstanceId = source.getPlatformInstanceId() == null ? "" : source.getPlatformInstanceId().trim();
        if (!platformInstanceId.isBlank()) {
            try { java.util.UUID.fromString(platformInstanceId); }
            catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("租户平台实例编号必须是UUID: " + id);
            }
        }
        this.miniappEnabled = source.isMiniappEnabled();
        this.miniappAppId = source.getMiniappAppId() == null ? "" : source.getMiniappAppId().trim();
        this.miniappSecret = source.getMiniappSecret() == null ? "" : source.getMiniappSecret();
        this.maximumPoolSize = source.getMaximumPoolSize();
        this.minimumIdle = source.getMinimumIdle();
        this.connectionTimeoutMs = source.getConnectionTimeoutMs();
        if (maximumPoolSize < 1 || maximumPoolSize > 100) {
            throw new IllegalArgumentException("租户连接池上限必须在1至100之间");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("租户连接池最小空闲数无效");
        }
        if (connectionTimeoutMs < 250 || connectionTimeoutMs > 60_000) {
            throw new IllegalArgumentException("租户连接超时必须在250至60000毫秒之间");
        }
    }

    public String id() { return id; }
    public boolean active() { return active; }
    public List<String> hosts() { return hosts; }
    public String storageRoot() { return storageRoot; }
    public String jdbcUrl() { return jdbcUrl; }
    public String username() { return username; }
    public String password() { return password; }
    public String driverClassName() { return driverClassName; }
    public String platformCustomerId() { return platformCustomerId; }
    public String platformInstanceId() { return platformInstanceId; }
    public boolean miniappEnabled() { return miniappEnabled; }
    public String miniappAppId() { return miniappAppId; }
    public String miniappSecret() { return miniappSecret; }
    public int maximumPoolSize() { return maximumPoolSize; }
    public int minimumIdle() { return minimumIdle; }
    public long connectionTimeoutMs() { return connectionTimeoutMs; }

    public boolean hasDataSource() {
        return !jdbcUrl.isBlank() && !username.isBlank() && !driverClassName.isBlank();
    }

    @Override
    public String toString() {
        return "TenantDefinition[id=" + id + ", active=" + active + ", hosts=" + hosts.size() + "]";
    }
}
