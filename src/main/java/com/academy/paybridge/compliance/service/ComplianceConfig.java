package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ComplianceProperties.class, AmlProperties.class})
class ComplianceConfig {
}
