package com.academy.paybridge.shared.logging;

import com.academy.paybridge.shared.config.RequestIdFilter;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Gives a scheduled job run its own correlation id, for example {@code dispatcher-3f9a1c52}.
 * <p>
 * Timers are not triggered by a web request, so there is no request id to carry. Without this their log lines show an
 * empty id; with it, every line from one run of the job shares a tag you can search for.
 */
public final class JobCorrelation {

    private JobCorrelation() {
    }

    public static void run(String jobName, Runnable work) {
        String previous = MDC.get(RequestIdFilter.MDC_KEY);
        MDC.put(RequestIdFilter.MDC_KEY, jobName + "-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            work.run();
        } finally {
            if (previous == null) {
                MDC.remove(RequestIdFilter.MDC_KEY);
            } else {
                MDC.put(RequestIdFilter.MDC_KEY, previous);
            }
        }
    }
}
