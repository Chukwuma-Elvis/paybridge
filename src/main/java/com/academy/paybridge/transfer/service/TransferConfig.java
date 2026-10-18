package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.client.WebhookProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TransferProperties.class, WebhookProperties.class})
class TransferConfig {
}
