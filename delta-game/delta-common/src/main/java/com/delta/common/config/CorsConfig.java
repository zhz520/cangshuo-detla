package com.delta.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.URI;

@Configuration
public class CorsConfig {
    @org.springframework.beans.factory.annotation.Value("${app.allowed-origins:https://jax.kkimg.top,https://jaxapi.kkimg.top}")
    private String allowedOrigins;
    @Autowired(required = false) private TenantProperties tenantProperties;
    @Autowired(required = false) private TenantRegistry tenantRegistry;
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        if(tenantProperties!=null&&tenantProperties.isEnabled()){
            return request->{
                var tenant=tenantRegistry.findByHost(request.getServerName()).filter(t->t.active()).orElse(null);
                if(tenant==null)return null;
                String origin=request.getHeader("Origin");
                if(origin==null||origin.isBlank())return null;
                try{
                    URI uri=URI.create(origin);
                    if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))
                            ||uri.getHost()==null||uri.getUserInfo()!=null)return null;
                    if(tenantRegistry.findByHost(uri.getHost()).filter(t->t.active()&&t.id().equals(tenant.id())).isEmpty())return null;
                    CorsConfiguration config=new CorsConfiguration();
                    config.setAllowCredentials(false);
                    config.setAllowedOrigins(java.util.List.of(origin));
                    config.addAllowedHeader("*");
                    config.addAllowedMethod("*");
                    config.setMaxAge(1800L);
                    return config;
                }catch(IllegalArgumentException error){return null;}
            };
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(false);
        config.setAllowedOrigins(java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setMaxAge(1800L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
