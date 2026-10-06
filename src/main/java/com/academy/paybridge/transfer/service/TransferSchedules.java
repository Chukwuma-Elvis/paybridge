package com.academy.paybridge.transfer.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The alarm clock that wakes the dispatcher. Kept apart from it so tests can switch the clock off
 * ({@code paybridge.dispatcher.enabled=false}) and call the work directly, one step at a time.
 */
@Component
@ConditionalOnProperty(name = "paybridge.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
class DispatcherSchedule {

    private final TransferDispatcher dispatcher;

    DispatcherSchedule(TransferDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${paybridge.dispatcher.interval:2s}")
    void run() {
        dispatcher.dispatchDue();
    }
}

/** The alarm clock that wakes the reconciler. Switch it off with {@code paybridge.reconciler.enabled=false}. */
@Component
@ConditionalOnProperty(name = "paybridge.reconciler.enabled", havingValue = "true", matchIfMissing = true)
class ReconcilerSchedule {

    private final TransferReconciler reconciler;

    ReconcilerSchedule(TransferReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${paybridge.reconciler.interval:5s}")
    void run() {
        reconciler.reconcile();
    }
}
