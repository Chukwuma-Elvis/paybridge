package com.academy.paybridge.transfer.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tiny check against the REAL Paystack, using your TEST key. It is skipped unless the environment variable
 * {@code PAYSTACK_SECRET_KEY} holds a key starting {@code sk_test_}, so an ordinary build never touches the network.
 * <p>
 * It never sends money: it only asks about a reference that cannot exist. A pass proves the key is accepted and that the address,
 * the headers and the "not found" shape are what this client expects. Run it with:
 * <pre>PAYSTACK_SECRET_KEY=sk_test_... ./mvnw test -Dtest=PaystackLiveSmokeTest</pre>
 */
@EnabledIfEnvironmentVariable(named = "PAYSTACK_SECRET_KEY", matches = "sk_test_.+")
class PaystackLiveSmokeTest {

    @Test
    void theTestKeyIsAcceptedAndAnUnknownTransferIsNotFound() {
        PaystackProperties properties = new PaystackProperties(System.getenv("PAYSTACK_SECRET_KEY"), "https://api.paystack.co",
                Duration.ofSeconds(5), Duration.ofSeconds(15));
        PaystackTransferGateway gateway = new PaystackTransferGateway(properties);

        GatewayTransferResult result = gateway.fetchTransferStatus("pb-smoke-test-does-not-exist-0001");

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.NOT_FOUND);
    }
}
