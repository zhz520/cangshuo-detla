package com.delta.common.job.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "tenant", name = "enabled", havingValue = "false", matchIfMissing = true)
public class ScheduleConfig {}
