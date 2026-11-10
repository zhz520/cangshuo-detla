package com.delta.common.tenant;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/** Returns the configured mini-program client for the current tenant only. */
@Component
public class TenantWxMaServiceProvider {
    @Autowired(required=false) private TenantProperties tenantProperties;
    @Autowired(required=false) private TenantRegistry tenantRegistry;
    @Autowired(required=false) private ObjectProvider<WxMaService> fallback;
    private final ConcurrentHashMap<String,WxMaService> services=new ConcurrentHashMap<>();

    public WxMaService get(){
        if(tenantProperties==null||!tenantProperties.isEnabled()){
            return fallback==null?null:fallback.getIfAvailable();
        }
        String tenantId=TenantContext.requireId();
        TenantDefinition tenant=tenantRegistry==null?null:tenantRegistry.findById(tenantId).orElse(null);
        if(tenant==null||!tenant.active()||!tenant.miniappEnabled()
                ||tenant.miniappAppId().isBlank()||tenant.miniappSecret().isBlank())return null;
        return services.computeIfAbsent(tenantId,key->{
            WxMaDefaultConfigImpl config=new WxMaDefaultConfigImpl();
            config.setAppid(tenant.miniappAppId());
            config.setSecret(tenant.miniappSecret());
            WxMaServiceImpl service=new WxMaServiceImpl();
            service.setWxMaConfig(config);
            return service;
        });
    }
}
