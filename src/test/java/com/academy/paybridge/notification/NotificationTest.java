package com.academy.paybridge.notification;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.notification.service.NotificationService;
import com.academy.paybridge.transfer.api.TransferCompleted;
import com.academy.paybridge.transfer.api.TransferFailed;
import com.academy.paybridge.transfer.api.TransferReversed;
import com.academy.paybridge.transfer.api.TransferType;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationTest extends AbstractIntegrationTest {

    @Autowired
    AccountApi accountApi;

    @Autowired
    NotificationService notificationService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private UUID customerOf(UUID accountId) {
        return accountApi.getAccount(accountId).ownerCustomerId();
    }

    private int notificationsFor(UUID customerId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from notification where customer_id = ?", Integer.class, customerId);
        return count == null ? 0 : count;
    }

    private String transfer(UUID from, UUID to, long amount) throws Exception {
        String body = """
                {"sourceAccountId": "%s", "destinationAccountNumber": "%s", "amountMinor": %d}
                """.formatted(from, accountApi.getAccount(to).accountNumber(), amount);
        return mockMvc.perform(post("/api/transfers/internal")
                        .header("Idempotency-Key", "notify-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void bothSenderAndReceiverAreToldAboutAnInternalTransfer() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID to = newAccount();
        UUID sender = customerOf(from);
        UUID receiver = customerOf(to);

        String reference = JsonPath.read(transfer(from, to, 5_000), "$.reference");

        // the listener runs after the transfer has committed, so wait for it
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(notificationsFor(sender)).isEqualTo(2);   // SMS + email
            assertThat(notificationsFor(receiver)).isEqualTo(2);
        });

        mockMvc.perform(get("/api/notifications").param("customerId", sender.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventType").value("TRANSFER_SENT"))
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.containsString("NGN 50.00")))
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.containsString(reference)));
        mockMvc.perform(get("/api/notifications").param("customerId", receiver.toString()))
                .andExpect(jsonPath("$[0].eventType").value("TRANSFER_RECEIVED"));
    }

    @Test
    void theEventIsMarkedHandledOnceTheListenerFinishes() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID to = newAccount();
        String reference = JsonPath.read(transfer(from, to, 1_000), "$.reference");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer unfinished = jdbcTemplate.queryForObject(
                    "select count(*) from event_publication where completion_date is null "
                            + "and serialized_event like ?", Integer.class, "%" + reference + "%");
            Integer finished = jdbcTemplate.queryForObject(
                    "select count(*) from event_publication where completion_date is not null "
                            + "and serialized_event like ?", Integer.class, "%" + reference + "%");
            assertThat(unfinished).isZero();
            assertThat(finished).isPositive();
        });
    }

    @Test
    void theSameEventDeliveredTwiceNeverAlertsACustomerTwice() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID to = newAccount();
        UUID sender = customerOf(from);
        UUID receiver = customerOf(to);
        TransferCompleted event = new TransferCompleted(UUID.randomUUID(), "pb-dup-" + UUID.randomUUID(),
                TransferType.INTERNAL, sender, from, to, 5_000, 0, "NGN");

        notificationService.onTransferCompleted(event);
        notificationService.onTransferCompleted(event); // redelivery after a crash

        assertThat(notificationsFor(sender)).isEqualTo(2);
        assertThat(notificationsFor(receiver)).isEqualTo(2);
    }

    @Test
    void anExternalTransferAlertMentionsTheFee() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID sender = customerOf(from);
        TransferCompleted event = new TransferCompleted(UUID.randomUUID(), "pb-ext-" + UUID.randomUUID(),
                TransferType.EXTERNAL, sender, from, null, 500_000, 2_500, "NGN");

        notificationService.onTransferCompleted(event);

        assertThat(notificationService.listForCustomer(sender, 10))
                .hasSize(2)
                .allSatisfy(n -> assertThat(n.getMessage())
                        .contains("NGN 5,000.00").contains("Fee: NGN 25.00").contains(event.reference()));
    }

    @Test
    void failedAndReversedTransfersTellTheCustomerTheMoneyIsBack() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID sender = customerOf(from);

        notificationService.onTransferFailed(new TransferFailed(UUID.randomUUID(), "pb-f-" + UUID.randomUUID(),
                sender, from, 100_000, "NGN", "recipient bank unavailable"));
        notificationService.onTransferReversed(new TransferReversed(UUID.randomUUID(), "pb-r-" + UUID.randomUUID(),
                sender, from, 100_000, "NGN", "returned by recipient bank"));

        assertThat(notificationService.listForCustomer(sender, 10))
                .hasSize(4)
                .allSatisfy(n -> assertThat(n.getMessage()).contains("returned to your account"));
    }
}
