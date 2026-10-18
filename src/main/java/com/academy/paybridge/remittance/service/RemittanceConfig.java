package com.academy.paybridge.remittance.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RemittanceProperties.class)
class RemittanceConfig {
}
