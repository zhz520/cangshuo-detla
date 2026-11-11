package com.delta.common.websocket;

import com.delta.common.security.service.RateLimitService;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantDefinition;
import com.delta.common.tenant.TenantHosts;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;
import java.net.URI;

@Component
@RequiredArgsConstructor
public class ChatHandshakeInterceptor implements HandshakeInterceptor {
    private final RateLimitService limits;
    private final TenantRegistry tenants;
    private final TenantProperties tenantProperties;
    @Override
    public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes) {
        String remote=request.getRemoteAddress()==null ? "unknown" : request.getRemoteAddress().getAddress().getHostAddress();
        TenantDefinition tenant=null;
        if(tenantProperties.isEnabled()){
            tenant=tenants.findByHost(request.getURI().getHost()).orElse(null);
            if(tenant==null||!tenant.active()||!sameTenantOrigin(request,tenant.id())){
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            try(TenantContext.Scope ignored=TenantContext.open(tenant.id())){
                try{limits.check("ws-handshake:"+remote,30,60);}
                catch(Exception e){response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);return false;}
            }
        }else{
            try{limits.check("ws-handshake:"+remote,30,60);}
            catch(Exception e){response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);return false;}
        }
        attributes.put("remoteIp",remote);
        if(tenant!=null)attributes.put("tenantId",tenant.id());
        return true;
    }
    private boolean sameTenantOrigin(ServerHttpRequest request,String tenantId){
        String origin=request.getHeaders().getOrigin();
        if(origin==null||origin.isBlank())return false;
        try{
            URI uri=URI.create(origin);
            if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))
                    ||uri.getHost()==null||uri.getUserInfo()!=null)return false;
            return tenants.findByHost(TenantHosts.normalize(uri.getHost()))
                    .filter(t->t.active()&&t.id().equals(tenantId)).isPresent();
        }catch(IllegalArgumentException error){return false;}
    }
    @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception) {}
}
