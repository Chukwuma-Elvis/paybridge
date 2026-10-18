package com.academy.paybridge.fx.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FxProperties.class)
class FxConfig {
}
