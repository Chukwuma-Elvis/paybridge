package com.academy.paybridge.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks what the access log writes, by listening to the filter's real logger. */
class AccessLogFilterTest {

    private static final String LOGGER_NAME = AccessLogFilter.class.getName();

    private final AccessLogFilter filter = new AccessLogFilter();
    private final CapturingAppender appender = new CapturingAppender();
    private LoggerContext context;

    @BeforeEach
    void listenToTheFilterLog() {
        context = (LoggerContext) LogManager.getContext(AccessLogFilter.class.getClassLoader(), false);
        Configurator.setLevel(LOGGER_NAME, Level.DEBUG); // DEBUG so quiet health-check lines are captured too
        appender.start();
        LoggerConfig loggerConfig = context.getConfiguration().getLoggerConfig(LOGGER_NAME);
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        context.updateLoggers();
    }

    @AfterEach
    void stopListening() {
        context.getConfiguration().getLoggerConfig(LOGGER_NAME).removeAppender(appender.getName());
        appender.stop();
        Configurator.setLevel(LOGGER_NAME, (Level) null);
        context.updateLoggers();
    }

    @Test
    void aSuccessfulRequestIsLoggedAtInfoWithMethodPathStatusAndTime() throws Exception {
        run("POST", "/api/transfers/internal", null, respondingWith(201));

        assertThat(appender.levels()).containsExactly(Level.INFO);
        assertThat(appender.messages().get(0))
                .startsWith("POST /api/transfers/internal -> 201 (")
                .endsWith(" ms)");
    }

    @Test
    void theQueryStringIsPartOfTheLoggedTarget() throws Exception {
        run("GET", "/api/accounts", "customerId=abc-123", respondingWith(200));

        assertThat(appender.messages().get(0)).startsWith("GET /api/accounts?customerId=abc-123 -> 200 (");
    }

    @Test
    void aClientErrorIsLoggedAtWarn() throws Exception {
        run("GET", "/api/accounts/does-not-exist", null, respondingWith(404));

        assertThat(appender.levels()).containsExactly(Level.WARN);
        assertThat(appender.messages().get(0)).contains("-> 404");
    }

    @Test
    void aServerErrorIsLoggedAtError() throws Exception {
        run("POST", "/api/transfers/internal", null, respondingWith(500));

        assertThat(appender.levels()).containsExactly(Level.ERROR);
        assertThat(appender.messages().get(0)).contains("-> 500");
    }

    @Test
    void aRequestThatEndsInAnExceptionIsStillLoggedAsAnErrorAndTheExceptionIsNotSwallowed() {
        FilterChain exploding = (request, response) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> run("GET", "/api/customers/1", null, exploding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(appender.levels()).containsExactly(Level.ERROR);
        assertThat(appender.messages().get(0)).startsWith("GET /api/customers/1 -> 500 (");
    }

    @Test
    void aHealthCheckThatSucceedsIsOnlyLoggedAtDebugSoItDoesNotDrownRealTraffic() throws Exception {
        run("GET", "/actuator/health", null, respondingWith(200));

        assertThat(appender.levels()).containsExactly(Level.DEBUG);
    }

    @Test
    void aHealthCheckThatFailsIsStillLoudlyLogged() throws Exception {
        run("GET", "/actuator/health", null, respondingWith(503));

        assertThat(appender.levels()).containsExactly(Level.ERROR);
    }

    @Test
    void aVeryLongQueryStringIsCut() throws Exception {
        run("GET", "/api/notifications", "x=" + "a".repeat(2_000), respondingWith(200));

        assertThat(appender.messages().get(0).length()).isLessThan(400);
        assertThat(appender.messages().get(0)).contains("...");
    }

    @Test
    void requestBodiesAndHeadersAreNeverLogged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/customers");
        request.setContent("{\"email\":\"secret@example.com\"}".getBytes());
        request.addHeader("Authorization", "Bearer top-secret-key");

        filter.doFilter(request, new MockHttpServletResponse(), respondingWith(201));

        assertThat(appender.messages().get(0)).doesNotContain("secret").doesNotContain("Bearer");
    }

    // ------------------------------------------------------------------------------------------------------------

    private void run(String method, String path, String query, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setQueryString(query);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    private static FilterChain respondingWith(int status) {
        return (request, response) -> ((HttpServletResponse) response).setStatus(status);
    }

    private static class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("access-log-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<Level> levels() {
            return events.stream().map(LogEvent::getLevel).toList();
        }

        List<String> messages() {
            return events.stream().map(e -> e.getMessage().getFormattedMessage()).toList();
        }
    }
}
