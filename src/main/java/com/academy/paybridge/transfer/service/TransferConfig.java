package com.academy.paybridge.transfer.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TransferProperties.class)
class TransferConfig {
}
