package com.academy.paybridge.shared.logging;

import com.academy.paybridge.shared.config.RequestIdFilter;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Teaches Spring's background executor how to carry the request id from one thread to another.
 * <p>
 * The request id lives in the MDC, a notepad that belongs to ONE thread. When work is handed to a background thread
 * (for example an alert sent after a transfer commits), the new thread starts with a blank notepad and its log lines
 * lose the id.
 * <p>
 * Spring Modulith installs Spring's {@code ContextPropagatingTaskDecorator} on the application's executors, replacing
 * any decorator of our own. That decorator copies every "thread-local accessor" registered with Micrometer's context
 * registry. So the way in is to register one: this class. It is found through
 * {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor}.
 * <p>
 * Three questions the executor asks of it: what is the value on this thread? (capture, when work is submitted); put it on
 * the worker thread (setValue, just before the work runs); and clear it again afterwards (reset), because worker threads
 * are reused.
 */
public class RequestIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

    public static final String KEY = "paybridge.requestId";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(RequestIdFilter.MDC_KEY, value);
    }

    @Override
    public void setValue() {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @Override
    public void reset() {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }
}
