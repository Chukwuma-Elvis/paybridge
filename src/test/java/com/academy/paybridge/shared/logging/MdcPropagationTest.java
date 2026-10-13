package com.academy.paybridge.shared.logging;

import com.academy.paybridge.shared.config.RequestIdFilter;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request id is carried between threads by Micrometer's context propagation, using {@link RequestIdThreadLocalAccessor}
 * (registered through META-INF/services). These tests drive that mechanism by hand: capture on one thread, replay on another.
 */
class MdcPropagationTest {

    private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Runs the task on a fresh thread inside the captured context, as Spring's executor does, and returns what it saw. */
    private String runOnAnotherThreadWith(ContextSnapshot snapshot, Runnable beforeTask) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("not run");
        Thread worker = new Thread(() -> {
            beforeTask.run();
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                seen.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });
        worker.start();
        worker.join();
        return seen.get();
    }

    @Test
    void theAccessorIsRegisteredAutomatically() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");

        ContextSnapshot snapshot = snapshots.captureAll();

        MDC.clear();
        try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("req-42");
        }
    }

    @Test
    void theRequestIdFollowsTheWorkOntoAnotherThread() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        ContextSnapshot snapshot = snapshots.captureAll();

        assertThat(runOnAnotherThreadWith(snapshot, () -> { })).isEqualTo("req-42");
    }

    @Test
    void theIdIsTakenWhenTheWorkIsHandedOverNotWhenItRuns() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "first");
        ContextSnapshot snapshot = snapshots.captureAll();
        MDC.put(RequestIdFilter.MDC_KEY, "changed-after-handover");

        assertThat(runOnAnotherThreadWith(snapshot, () -> { })).isEqualTo("first");
    }

    @Test
    void theWorkerThreadIsLeftCleanForTheNextTask() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        ContextSnapshot snapshot = snapshots.captureAll();
        AtomicReference<String> leftBehind = new AtomicReference<>("not set");

        Thread worker = new Thread(() -> {
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                // the task runs here
            }
            leftBehind.set(MDC.get(RequestIdFilter.MDC_KEY));
        });
        worker.start();
        worker.join();

        assertThat(leftBehind.get()).isNull();
    }

    @Test
    void aWorkersOwnIdIsRestoredAfterTheTask() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "from-request");
        ContextSnapshot snapshot = snapshots.captureAll();
        AtomicReference<String> afterwards = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            MDC.put(RequestIdFilter.MDC_KEY, "workers-own");
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                // the task runs here
            }
            afterwards.set(MDC.get(RequestIdFilter.MDC_KEY));
        });
        worker.start();
        worker.join();

        assertThat(afterwards.get()).isEqualTo("workers-own");
    }

    @Test
    void aTaskHandedOverWithNoIdRunsWithNone() throws Exception {
        ContextSnapshot snapshot = snapshots.captureAll();

        assertThat(runOnAnotherThreadWith(snapshot, () -> { })).isNull();
    }

    @Test
    void aScheduledJobGetsItsOwnTaggedIdAndGivesItBack() {
        AtomicReference<String> during = new AtomicReference<>();

        JobCorrelation.run("dispatcher", () -> during.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(during.get()).matches("dispatcher-[0-9a-f]{8}");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void eachRunOfAJobGetsAFreshId() {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        JobCorrelation.run("reconciler", () -> first.set(MDC.get(RequestIdFilter.MDC_KEY)));
        JobCorrelation.run("reconciler", () -> second.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(first.get()).isNotEqualTo(second.get());
    }

    @Test
    void anExistingIdIsRestoredAndTheExceptionIsNotSwallowedIfTheJobThrows() {
        MDC.put(RequestIdFilter.MDC_KEY, "outer");

        try {
            JobCorrelation.run("dispatcher", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the exception is not swallowed
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("outer");
    }
}
