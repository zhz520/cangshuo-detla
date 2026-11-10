package com.delta.common.tenant;

import com.delta.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Routes every JDBC checkout to the tenant selected by trusted request or task context. */
@Slf4j
public class TenantRoutingDataSource extends AbstractRoutingDataSource implements AutoCloseable {
    private final Map<String, DataSource> targets;

    public TenantRoutingDataSource(Map<String, DataSource> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("租户数据源不能为空");
        }
        Map<String, DataSource> safe = new LinkedHashMap<>();
        for (Map.Entry<String, DataSource> entry : targets.entrySet()) {
            safe.put(TenantIds.requireValid(entry.getKey()), entry.getValue());
        }
        this.targets = Map.copyOf(safe);
        setTargetDataSources(new LinkedHashMap<>(this.targets));
        setLenientFallback(false);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.currentIdOrNull();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        String tenantId = TenantContext.requireId();
        DataSource target = targets.get(tenantId);
        if (target == null) {
            throw new BusinessException(503, "当前租户的数据源不可用，请联系维护人员");
        }
        return target;
    }

    public int tenantCount() {
        return targets.size();
    }

    @Override
    public void close() {
        for (Map.Entry<String, DataSource> entry : targets.entrySet()) {
            try {
                if (entry.getValue() instanceof AutoCloseable closeable) closeable.close();
            } catch (Exception error) {
                log.warn("关闭租户[{}]数据源失败", entry.getKey(), error);
            }
        }
    }
}
