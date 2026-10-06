package com.academy.paybridge.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Switches on {@code @Scheduled} methods (the transfer dispatcher and reconciler use them). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
