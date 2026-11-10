package com.delta.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.regex.Pattern;

@Configuration
public class SwaggerConfig implements WebMvcConfigurer {

    @Value("${file.upload.path:D:/data/upload}")
    private String uploadPath;
    @Autowired(required = false) private TenantProperties tenantProperties;
    @Autowired(required = false) private TenantRegistry tenantRegistry;
    private static final Pattern FILE_NAME=Pattern.compile("\\d{4}/\\d{2}/\\d{2}/[a-fA-F0-9-]{32,36}\\.(?i:png|jpg|jpeg|gif|webp|mp3|m4a|wav|aac|ogg|webm)");
    private static final Pattern TENANT_FILE=Pattern.compile("([a-z0-9][a-z0-9-]{0,47})/(\\d{4}/\\d{2}/\\d{2}/[a-fA-F0-9-]{32,36}\\.(?i:png|jpg|jpeg|gif|webp|mp3|m4a|wav|aac|ogg|webm))");

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info().title("三角洲游戏平台 API").version("1.0").description("Delta Game Boosting Platform"))
                .addSecurityItem(new SecurityRequirement().addList("Authorization"))
                .schemaRequirement("Authorization",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Swagger
        registry.addResourceHandler("doc.html")
                .addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
        // 上传文件静态资源映射: /file/** -> 本地磁盘
        String location = "file:" + uploadPath.replace("\\", "/");
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler("/file/**")
                .addResourceLocations(location)
                .setCacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(365))
                        .cachePublic().immutable().staleWhileRevalidate(java.time.Duration.ofDays(7)))
                .resourceChain(true).addResolver(new org.springframework.web.servlet.resource.PathResourceResolver() {
                    @Override protected org.springframework.core.io.Resource getResource(String resourcePath,org.springframework.core.io.Resource location) throws java.io.IOException {
                        if(tenantProperties!=null&&tenantProperties.isEnabled()){
                            var matcher=TENANT_FILE.matcher(resourcePath);
                            if(matcher.matches() && tenantRegistry!=null
                                    && tenantRegistry.findById(matcher.group(1)).filter(t->t.active()).isPresent())return super.getResource(resourcePath,location);
                            // During a one-tenant pilot, the historical upload root is
                            // still the only source of existing image URLs. Do not
                            // permit a shared root once multiple active tenants exist.
                            if(tenantRegistry!=null&&tenantRegistry.activeTenants().size()==1
                                    &&FILE_NAME.matcher(resourcePath).matches())return super.getResource(resourcePath,location);
                            return null;
                        }else if(!FILE_NAME.matcher(resourcePath).matches())return null;
                        return super.getResource(resourcePath,location);
                    }
                });
    }
}
