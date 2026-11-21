package com.delta.reporting.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class AnalyticsWebConfig implements WebMvcConfigurer {
    private final AnalyticsVisitInterceptor visitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitInterceptor).addPathPatterns("/**");
    }
}
