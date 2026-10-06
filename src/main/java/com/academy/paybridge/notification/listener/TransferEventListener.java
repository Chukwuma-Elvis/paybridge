package com.academy.paybridge.notification.listener;

import com.academy.paybridge.notification.service.NotificationService;
import com.academy.paybridge.transfer.api.TransferCompleted;
import com.academy.paybridge.transfer.api.TransferFailed;
import com.academy.paybridge.transfer.api.TransferReversed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Listens for things that happened in the transfer module. The transfer module has no idea this class exists.
 * <p>
 * {@link ApplicationModuleListener} means: run after the transfer's transaction has committed, in its own
 * transaction and thread, and keep the event on record until we finish, so a crash cannot lose it.
 * A failing notification can therefore never undo or delay a transfer.
 */
@Component
class TransferEventListener {

    private final NotificationService notificationService;

    TransferEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    void on(TransferCompleted event) {
        notificationService.onTransferCompleted(event);
    }

    @ApplicationModuleListener
    void on(TransferFailed event) {
        notificationService.onTransferFailed(event);
    }

    @ApplicationModuleListener
    void on(TransferReversed event) {
        notificationService.onTransferReversed(event);
    }
}
