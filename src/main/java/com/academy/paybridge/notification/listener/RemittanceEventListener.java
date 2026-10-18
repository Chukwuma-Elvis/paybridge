package com.academy.paybridge.notification.listener;

import com.academy.paybridge.notification.service.NotificationService;
import com.academy.paybridge.remittance.api.RemittancePaid;
import com.academy.paybridge.remittance.api.RemittanceRefunded;
import com.academy.paybridge.remittance.api.RemittanceUnderReview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Listens for things that happened in the remittance module, after its transaction has committed. The remittance module has no idea
 * this class exists, and a failing notification can never undo or delay a remittance.
 * <p>
 * Only the recipient can be told directly (when they are a PayBridge customer). The sender is a customer of the corridor partner, who
 * tells them; a refund or a hold is therefore recorded in the log for the operators, with the reference only, never a name or number.
 */
@Component
class RemittanceEventListener {

    private static final Logger log = LoggerFactory.getLogger(RemittanceEventListener.class);

    private final NotificationService notificationService;

    RemittanceEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    void on(RemittancePaid event) {
        notificationService.onRemittancePaid(event);
    }

    @ApplicationModuleListener
    void on(RemittanceRefunded event) {
        log.info("Remittance {} refunded: {}", event.reference(), event.reason());
    }

    @ApplicationModuleListener
    void on(RemittanceUnderReview event) {
        log.warn("Remittance {} is waiting for a compliance decision: {}", event.reference(), event.reason());
    }
}
