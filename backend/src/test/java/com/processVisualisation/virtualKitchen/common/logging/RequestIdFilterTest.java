package com.processVisualisation.virtualKitchen.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestIdAvailableInMdcAndEchoedInResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/recipes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];

        filter.doFilter(request, response, (req, res) -> seenInChain[0] = MDC.get(MdcKeys.REQUEST_ID));

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(header).matches("^[0-9a-f]{16}$");
        assertThat(seenInChain[0]).isEqualTo(header);
    }

    @Test
    void reusesValidInboundRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/recipes");
        request.addHeader(RequestIdFilter.HEADER, "frontend-req-1234");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get(MdcKeys.REQUEST_ID)).isEqualTo("frontend-req-1234"));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("frontend-req-1234");
    }

    @Test
    void replacesInboundIdThatCouldInjectIntoLogs() {
        assertThat(RequestIdFilter.resolveRequestId("abc\nINFO fake log line")).matches("^[0-9a-f]{16}$");
        assertThat(RequestIdFilter.resolveRequestId("short")).matches("^[0-9a-f]{16}$");
        assertThat(RequestIdFilter.resolveRequestId("x".repeat(65))).matches("^[0-9a-f]{16}$");
        assertThat(RequestIdFilter.resolveRequestId(null)).matches("^[0-9a-f]{16}$");
    }

    @Test
    void clearsMdcAfterRequestIncludingKeysAddedDownstream() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(),
                (req, res) -> MDC.put(MdcKeys.USER_ID, "17"));

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void clearsMdcAndLogsStatus500WhenChainThrows() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        try (LogCapture access = LogCapture.of(RequestIdFilter.ACCESS_LOGGER);
             LogCapture app = LogCapture.of(RequestIdFilter.class)) {
            assertThatThrownBy(() -> filter.doFilter(
                    new MockHttpServletRequest("POST", "/api/x"), new MockHttpServletResponse(), failing))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            assertThat(access.events()).hasSize(1);
            assertThat(access.events().get(0).getFormattedMessage()).contains("status=500").contains("path=/api/x");
            assertThat(access.events().get(0).getMDCPropertyMap()).containsKey(MdcKeys.REQUEST_ID);

            List<ILoggingEvent> crashed = app.events("http_request_crashed");
            assertThat(crashed).hasSize(1);
            assertThat(crashed.get(0).getLevel()).isEqualTo(Level.ERROR);
        }
    }

    @Test
    void writesEveryRequestToTheAccessLogOnlyWithoutQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/recipes");
        request.setQueryString("email=someone%40example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture access = LogCapture.of(RequestIdFilter.ACCESS_LOGGER);
             LogCapture app = LogCapture.of(RequestIdFilter.class)) {
            filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(201));

            assertThat(access.events()).hasSize(1);
            assertThat(access.events().get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(access.events().get(0).getFormattedMessage())
                    .contains("method=GET", "path=/api/v1/recipes", "status=201", "durationMs=")
                    .doesNotContain("email");
            assertThat(app.events()).as("a fast, successful request stays out of application.log").isEmpty();
        }
    }

    @Test
    void stateChangingRequestWritesOneActionCompletedLineWithOutcomeAndUsage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/recipes/63");
        request.addHeader(RequestIdFilter.UI_ACTION_HEADER, "editor.save");

        try (LogCapture app = LogCapture.of(RequestIdFilter.class)) {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                MDC.put(MdcKeys.ACTION, "recipe.update");
                UsageLedger.increment(UsageLedger.Counter.DB_WRITES);
                ((HttpServletResponse) res).setStatus(409);
            });

            List<ILoggingEvent> completed = app.events("action_completed");
            assertThat(completed).hasSize(1);
            assertThat(completed.get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(completed.get(0).getFormattedMessage())
                    .contains("method=PUT", "status=409", "outcome=rejected", "durationMs=", "aiRequests=0", "dbWrites=1");
            assertThat(completed.get(0).getMDCPropertyMap())
                    .containsEntry(MdcKeys.ACTION, "recipe.update")
                    .containsEntry(MdcKeys.UI_ACTION, "editor.save");
        }
    }

    @Test
    void rejectsAUiActionHeaderThatCouldForgeLogText() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x");
        request.addHeader(RequestIdFilter.UI_ACTION_HEADER, "save\nINFO forged");
        String[] seen = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen[0] = MDC.get(MdcKeys.UI_ACTION));

        assertThat(seen[0]).isNull();
    }

    @Test
    void outcomeFollowsTheStatusClass() {
        assertThat(RequestIdFilter.outcomeOf(201)).isEqualTo("success");
        assertThat(RequestIdFilter.outcomeOf(404)).isEqualTo("rejected");
        assertThat(RequestIdFilter.outcomeOf(502)).isEqualTo("failed");
    }

    @Test
    void slowRequestIsAlsoReportedToTheApplicationLog() throws Exception {
        ReflectionTestUtils.setField(filter, "slowRequestMs", 0L);
        try (LogCapture app = LogCapture.of(RequestIdFilter.class)) {
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/slow"), new MockHttpServletResponse(), (q, s) -> { });

            List<ILoggingEvent> slow = app.events("http_request_slow");
            assertThat(slow).hasSize(1);
            assertThat(slow.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(slow.get(0).getFormattedMessage()).contains("path=/api/v1/slow");
        } finally {
            ReflectionTestUtils.setField(filter, "slowRequestMs", 3000L);
        }
    }

    @Test
    void doesNotLogHealthChecksOrPreflights() throws Exception {
        try (LogCapture access = LogCapture.of(RequestIdFilter.ACCESS_LOGGER);
             LogCapture logs = LogCapture.of(RequestIdFilter.class)) {
            filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), (q, s) -> { });
            filter.doFilter(new MockHttpServletRequest("OPTIONS", "/api/x"), new MockHttpServletResponse(), (q, s) -> { });

            assertThat(access.events()).isEmpty();
            assertThat(logs.events()).isEmpty();
        }
    }

    @Test
    void concurrentRequestsNeverShareOrLeakRequestIds() throws Exception {
        int requests = 200;
        ExecutorService servletThreads = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String[]>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(servletThreads.submit(() -> {
                    start.await();
                    String before = MDC.get(MdcKeys.REQUEST_ID);
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    String[] seen = new String[2];
                    filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), response, (req, res) -> {
                        seen[0] = MDC.get(MdcKeys.REQUEST_ID);
                        LockSupport.parkNanos(1_000_000L);
                        seen[1] = MDC.get(MdcKeys.REQUEST_ID);
                    });
                    return new String[]{before, seen[0], seen[1], response.getHeader(RequestIdFilter.HEADER),
                            MDC.get(MdcKeys.REQUEST_ID)};
                }));
            }
            start.countDown();

            Set<String> ids = new HashSet<>();
            for (Future<String[]> future : results) {
                String[] r = future.get(30, TimeUnit.SECONDS);
                assertThat(r[0]).as("MDC before the request").isNull();
                assertThat(r[1]).as("ID seen by the handler").isEqualTo(r[3]);
                assertThat(r[2]).as("ID still the same later in the handler").isEqualTo(r[3]);
                assertThat(r[4]).as("MDC after the request").isNull();
                ids.add(r[3]);
            }
            assertThat(ids).hasSize(requests);
        } finally {
            servletThreads.shutdownNow();
        }
    }
}
