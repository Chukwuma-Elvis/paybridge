package com.academy.paybridge.shared.logging;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.shared.config.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit tests prove the decorator copies the id; this proves the application's REAL background executor actually
 * uses it. (Declaring the decorator bean is not enough if the executor that runs the alert listeners never picks it up.)
 */
class AsyncExecutorWiringTest extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private AsyncTaskExecutor applicationTaskExecutor;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void theDefaultBackgroundExecutorCarriesTheRequestIdAcross() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-from-the-submitter");

        String seenOnTheWorker = applicationTaskExecutor.submit(() -> MDC.get(RequestIdFilter.MDC_KEY)).get(5, TimeUnit.SECONDS);

        assertThat(seenOnTheWorker).isEqualTo("req-from-the-submitter");
    }
}
