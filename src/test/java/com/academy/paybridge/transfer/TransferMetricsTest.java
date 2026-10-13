package com.academy.paybridge.transfer;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.service.InternalTransferCommand;
import com.academy.paybridge.transfer.service.TransferService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferMetricsTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountApi accountApi;

    @Autowired
    private MeterRegistry meters;

    private double internalSuccesses() {
        Counter counter = meters.find("paybridge.transfers").tag("type", "INTERNAL").tag("outcome", "success").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aCommittedTransferIsCounted() throws Exception {
        UUID source = newFundedAccount(100_000);
        String destinationNumber = accountApi.getAccount(newAccount()).accountNumber();
        double before = internalSuccesses();

        transferService.transferInternal(new InternalTransferCommand(source, destinationNumber, 10_000, "count me"),
                "metrics-" + UUID.randomUUID());

        assertThat(internalSuccesses()).isEqualTo(before + 1);
    }

    @Test
    void aRepeatedRequestIsNotCountedTwice() throws Exception {
        UUID source = newFundedAccount(100_000);
        String destinationNumber = accountApi.getAccount(newAccount()).accountNumber();
        String key = "metrics-" + UUID.randomUUID();
        double before = internalSuccesses();

        transferService.transferInternal(new InternalTransferCommand(source, destinationNumber, 10_000, "once"), key);
        transferService.transferInternal(new InternalTransferCommand(source, destinationNumber, 10_000, "once"), key);

        assertThat(internalSuccesses()).isEqualTo(before + 1);
    }

    @Test
    void aTransferThatIsRefusedIsNotCounted() throws Exception {
        UUID source = newFundedAccount(1_000);
        String destinationNumber = accountApi.getAccount(newAccount()).accountNumber();
        double before = internalSuccesses();

        assertThatThrownBy(() -> transferService.transferInternal(
                new InternalTransferCommand(source, destinationNumber, 999_999, "too much"), "metrics-" + UUID.randomUUID()))
                .isInstanceOf(PayBridgeException.class);

        assertThat(internalSuccesses()).isEqualTo(before);
    }
}
