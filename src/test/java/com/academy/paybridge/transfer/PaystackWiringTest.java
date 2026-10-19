package com.academy.paybridge.transfer;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.transfer.client.FakeTransferGateway;
import com.academy.paybridge.transfer.client.PaystackTransferGateway;
import com.academy.paybridge.transfer.client.TransferGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** With {@code paybridge.gateway=paystack} the real client is the one the app uses, and the fake is switched off. */
@TestPropertySource(properties = {"paybridge.gateway=paystack", "paybridge.paystack.secret-key=sk_test_wiring_only_not_real"})
class PaystackWiringTest extends AbstractIntegrationTest {

    @Autowired List<TransferGateway> gateways;

    @Test
    void thePaystackClientReplacesTheFake() {
        assertThat(gateways).singleElement().isInstanceOf(PaystackTransferGateway.class);
        assertThat(gateways).noneMatch(g -> g instanceof FakeTransferGateway);
    }
}
