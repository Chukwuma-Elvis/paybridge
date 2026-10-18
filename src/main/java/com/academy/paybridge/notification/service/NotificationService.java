package com.academy.paybridge.notification.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.notification.domain.Notification;
import com.academy.paybridge.notification.repository.NotificationRepository;
import com.academy.paybridge.remittance.api.RemittancePaid;
import com.academy.paybridge.transfer.api.TransferCompleted;
import com.academy.paybridge.transfer.api.TransferFailed;
import com.academy.paybridge.transfer.api.TransferReversed;
import com.academy.paybridge.transfer.api.TransferType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSender sender;
    private final CustomerApi customerApi;
    private final AccountApi accountApi;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationSender sender,
                               CustomerApi customerApi,
                               AccountApi accountApi) {
        this.notificationRepository = notificationRepository;
        this.sender = sender;
        this.customerApi = customerApi;
        this.accountApi = accountApi;
    }

    public void onTransferCompleted(TransferCompleted event) {
        String amount = naira(event.amountMinor());

        if (event.type() == TransferType.INTERNAL) {
            AccountSummary destination = accountApi.getAccount(event.destinationAccountId());
            notifyCustomer(event.customerId(), "TRANSFER_SENT", event.reference(),
                    "You sent " + amount + " to account " + destination.accountNumber()
                            + ". Ref: " + event.reference());
            notifyCustomer(destination.ownerCustomerId(), "TRANSFER_RECEIVED", event.reference(),
                    "You received " + amount + " in your account " + destination.accountNumber()
                            + ". Ref: " + event.reference());
        } else {
            String fee = event.feeMinor() > 0 ? " Fee: " + naira(event.feeMinor()) + "." : "";
            notifyCustomer(event.customerId(), "TRANSFER_SENT", event.reference(),
                    "Your transfer of " + amount + " was successful." + fee + " Ref: " + event.reference());
        }
    }

    public void onTransferFailed(TransferFailed event) {
        notifyCustomer(event.customerId(), "TRANSFER_FAILED", event.reference(),
                "Your transfer of " + naira(event.amountMinor()) + " did not go through (" + event.reason()
                        + "). The money has been returned to your account. Ref: " + event.reference());
    }

    public void onTransferReversed(TransferReversed event) {
        notifyCustomer(event.customerId(), "TRANSFER_REVERSED", event.reference(),
                "Your transfer of " + naira(event.amountMinor()) + " was reversed (" + event.reason()
                        + "). The money has been returned to your account. Ref: " + event.reference());
    }

    /** The recipient has been paid. The message names the amount and the reference, never an account or identity-document number. */
    public void onRemittancePaid(RemittancePaid event) {
        if (event.recipientCustomerId() == null) {
            return;     // paid at another bank: that bank tells its own customer
        }
        notifyCustomer(event.recipientCustomerId(), "REMITTANCE_RECEIVED", event.reference(),
                "You received " + naira(event.buyMinor()) + " sent from abroad. Ref: " + event.reference());
    }

    @Transactional(readOnly = true)
    public List<Notification> listForCustomer(UUID customerId, int limit) {
        return notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, PageRequest.of(0, limit));
    }

    private void notifyCustomer(UUID customerId, String eventType, String reference, String message) {
        CustomerSummary customer = customerApi.getCustomer(customerId);
        deliver(customer.id(), Notification.Channel.SMS, customer.phone(), eventType, reference, message);
        deliver(customer.id(), Notification.Channel.EMAIL, customer.email(), eventType, reference, message);
    }

    private void deliver(UUID customerId, Notification.Channel channel, String recipient,
                         String eventType, String reference, String message) {
        // If the event system hands us the same event again (after a crash), do not tell the customer twice.
        if (notificationRepository.existsByCustomerIdAndEventTypeAndReferenceAndChannel(
                customerId, eventType, reference, channel)) {
            return;
        }
        notificationRepository.save(new Notification(customerId, channel, recipient, eventType, reference, message));
        sender.send(channel, recipient, message);
    }

    private static String naira(long kobo) {
        return "NGN " + String.format("%,d.%02d", kobo / 100, kobo % 100);
    }
}
