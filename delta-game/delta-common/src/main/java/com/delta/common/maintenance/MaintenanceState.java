package com.delta.common.maintenance;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import com.delta.common.tenant.TenantContext;

/**
 * 全局开关状态：控制接口是否对外开放。
 * true  表示正常对外提供服务
 * false 表示维护模式，除维护页面外所有接口拒绝访问
 *
 * 使用静态 AtomicBoolean，确保在任何上下文/Bean 实例中状态一致。
 */
@Component
public class MaintenanceState {

    private static final ConcurrentHashMap<String,AtomicBoolean> STATES = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return state().get();
    }

    public void setEnabled(boolean enabled) {
        state().set(enabled);
    }

    private AtomicBoolean state(){
        String tenant=TenantContext.currentIdOrNull();
        return STATES.computeIfAbsent(tenant==null?"default":tenant,key->new AtomicBoolean(true));
    }
}


