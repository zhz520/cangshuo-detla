package com.delta.admin.service;

import com.delta.platform.PlatformService;
import com.delta.system.service.SysConfigService;
import com.delta.notification.service.EmailNotificationService;
import com.delta.notification.service.EmailNotificationTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmailNotificationServiceContextTest {
    @Test
    void springSelectsTheProductionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(SysConfigService.class, () -> mock(SysConfigService.class));
            context.registerBean(PlatformService.class, () -> mock(PlatformService.class));
            context.registerBean(EmailNotificationTemplate.class, () -> mock(EmailNotificationTemplate.class));
            context.registerBean(EmailNotificationService.class);
            context.refresh();
            assertThat(context.getBean(EmailNotificationService.class)).isNotNull();
        }
    }
}
