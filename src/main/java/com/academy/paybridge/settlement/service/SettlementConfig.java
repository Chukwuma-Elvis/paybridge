package com.academy.paybridge.settlement.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SettlementProperties.class)
class SettlementConfig {
}
