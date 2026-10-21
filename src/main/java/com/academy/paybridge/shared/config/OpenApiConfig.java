package com.academy.paybridge.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The text at the top of the Swagger page and the "Authorize" button. Every endpoint (except health and webhooks)
 * needs an {@code X-API-Key} header, so the page is told about it once and adds it to every call you try.
 */
@Configuration
public class OpenApiConfig {

    static final String API_KEY_SCHEME = "ApiKey";

    @Bean
    public OpenAPI payBridgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayBridge API")
                        .description("Sandbox: pretend bank, partner and rate source. Click Authorize and paste an API key.")
                        .version("1"))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
