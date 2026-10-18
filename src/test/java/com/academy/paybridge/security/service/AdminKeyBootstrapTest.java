package com.academy.paybridge.security.service;

import com.academy.paybridge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminKeyBootstrapTest extends AbstractIntegrationTest {

    @Autowired
    private ApiKeyService apiKeyService;

    private static SecurityProperties withBootstrapKey(boolean enabled, String key) {
        return new SecurityProperties(enabled, key, new SecurityProperties.RateLimit(120, 30, 10, 600));
    }

    private static String strongKey() {
        return "bootstrap-" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void theOperatorsKeyBecomesAnAdminKeyAtStartup() {
        String key = strongKey();

        new AdminKeyBootstrap(withBootstrapKey(true, key), apiKeyService).run(new DefaultApplicationArguments());

        AuthenticatedClient client = apiKeyService.authenticate(key).orElseThrow();
        assertThat(client.isAdmin()).isTrue();
        assertThat(client.name()).isEqualTo("bootstrap-admin");
    }

    @Test
    void startingTwiceWithTheSameKeyIsHarmless() {
        String key = strongKey();
        AdminKeyBootstrap bootstrap = new AdminKeyBootstrap(withBootstrapKey(true, key), apiKeyService);

        bootstrap.run(new DefaultApplicationArguments());
        bootstrap.run(new DefaultApplicationArguments());

        assertThat(apiKeyService.authenticate(key)).isPresent();
    }

    @Test
    void aWeakKeyStopsTheApplicationFromStarting() {
        AdminKeyBootstrap bootstrap = new AdminKeyBootstrap(withBootstrapKey(true, "password123"), apiKeyService);

        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters")
                .hasMessageNotContaining("password123");
    }

    @Test
    void aKeyWithForbiddenCharactersStopsTheApplicationFromStarting() {
        String key = "has spaces and symbols !!! " + "a".repeat(40);

        assertThatThrownBy(() -> new AdminKeyBootstrap(withBootstrapKey(true, key), apiKeyService).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withNoKeyConfiguredNothingIsRegistered() {
        long before = apiKeyService.list().size();

        new AdminKeyBootstrap(withBootstrapKey(true, ""), apiKeyService).run(new DefaultApplicationArguments());

        assertThat((long) apiKeyService.list().size()).isEqualTo(before);
    }

    @Test
    void whenSecurityIsOffTheKeyIsIgnored() {
        String key = strongKey();

        new AdminKeyBootstrap(withBootstrapKey(false, key), apiKeyService).run(new DefaultApplicationArguments());

        assertThat(apiKeyService.authenticate(key)).isEmpty();
    }
}
