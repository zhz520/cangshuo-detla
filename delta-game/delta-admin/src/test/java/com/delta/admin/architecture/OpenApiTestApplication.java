package com.delta.admin.architecture;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Component;

/**
 * Test-only application that reuses the production component graph. Only
 * component classes compiled into {@code target/classes} are included, so
 * nested {@code @Configuration} classes from unrelated integration tests
 * cannot join the context or contribute probe endpoints to the document.
 */
@Configuration
@EnableAutoConfiguration
@MapperScan(basePackages = "com.delta.**.mapper")
@ComponentScan(basePackages = "com.delta", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = OpenApiTestApplication.MainComponentsOnlyFilter.class))
public class OpenApiTestApplication {

    public static class MainComponentsOnlyFilter implements TypeFilter {
        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
            try {
                String location = metadataReader.getResource().getURI().toString().replace('\\', '/');
                if (location.contains("/target/test-classes/")) return false;
            } catch (java.io.IOException ex) {
                return false;
            }
            String className = metadataReader.getClassMetadata().getClassName();
            if ("com.delta.admin.DeltaApplication".equals(className)) return false;
            // The expiration listener opens a Redis connection at startup; mappings do not need it.
            if ("com.delta.common.listener.OrderPayTimeoutListener".equals(className)) return false;
            // Mappings do not need background jobs, and scheduled jobs would pollute the test JVM.
            if ("com.delta.common.job.config.ScheduleConfig".equals(className)) return false;
            var metadata = metadataReader.getAnnotationMetadata();
            return metadata.hasAnnotation(Component.class.getName())
                    || metadata.hasMetaAnnotation(Component.class.getName());
        }
    }
}
