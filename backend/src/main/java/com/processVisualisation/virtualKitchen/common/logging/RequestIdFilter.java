package com.processVisualisation.virtualKitchen.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every HTTP request a request ID and makes it available to every log line written while handling it.
 * <p>
 * Runs first in the servlet filter chain (ahead of Spring Security), so authentication failures and the JWT
 * filter are covered too. A valid inbound {@value #HEADER} is reused so a caller can correlate its own logs;
 * anything else is replaced, which keeps client-controlled text out of the logs. The ID is echoed back in the
 * response header so a user or bug report can quote it. A valid {@value #UI_ACTION_HEADER} (the click that caused
 * the request) is kept as the {@code uiAction} MDC key.
 * <p>
 * Every request is written once, on completion, to the {@value #ACCESS_LOGGER} logger, which logback routes to
 * {@code logs/access.log} only: polling (job status, narration) would otherwise bury the operation log. The
 * application log gets:
 * <ul>
 *   <li>{@code event=action_completed} for every state-changing request (not GET/HEAD): the action, status,
 *       outcome, duration and what it used ({@link UsageLedger}: AI, database, cache);</li>
 *   <li>{@code event=http_request_slow} for a slow read ({@code app.logging.slow-request-ms});</li>
 *   <li>{@code event=http_request_crashed} when an exception escaped the exception handler.</li>
 * </ul>
 * Handled failures are also logged with their reason by {@code GlobalExceptionHandler}.
 * <p>
 * The MDC is cleared when the request finishes: servlet threads are pooled, so anything left behind would
 * leak into the next request served by the same thread. Work handed to a {@code TaskPool} carries a copy of
 * the MDC (see {@code ThreadPoolTaskPool}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";

    /** Optional client-side name of the click that caused the request, e.g. {@code editor.save}. */
    public static final String UI_ACTION_HEADER = "X-UI-Action";

    /** Logger for one line per HTTP request; logback sends it to access.log only. */
    public static final String ACCESS_LOGGER = "virtualKitchen.access";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Logger accessLog = LoggerFactory.getLogger(ACCESS_LOGGER);
    private static final Pattern VALID_INBOUND_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Pattern VALID_UI_ACTION = Pattern.compile("^[a-z0-9._-]{1,64}$");
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");

    @Value("${app.logging.slow-request-ms:3000}")
    private long slowRequestMs = 3000;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        MDC.put(MdcKeys.REQUEST_ID, requestId);
        String uiAction = request.getHeader(UI_ACTION_HEADER);
        if (uiAction != null && VALID_UI_ACTION.matcher(uiAction).matches()) {
            MDC.put(MdcKeys.UI_ACTION, uiAction);
        }
        response.setHeader(HEADER, requestId);

        boolean quiet = isQuiet(request);
        long startedAt = System.nanoTime();

        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            failed = true;
            throw ex;
        } finally {
            UsageLedger.Usage usage = UsageLedger.drain(UsageLedger.requestKey(requestId));
            if (!quiet) {
                // An exception escaping the chain means the container will answer 500, whatever the response says now.
                int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
                String method = request.getMethod();
                String path = request.getRequestURI();
                accessLog.info("method={} path={} status={} durationMs={}", method, path, status, durationMs);
                if (failed) {
                    log.error("event=http_request_crashed method={} path={} status={} durationMs={} {}",
                            method, path, status, durationMs, usage.toLogFields());
                } else if (!READ_METHODS.contains(method.toUpperCase())) {
                    String line = "event=action_completed method={} path={} status={} outcome={} durationMs={} {}";
                    Object[] args = {method, path, status, outcomeOf(status), durationMs, usage.toLogFields()};
                    if (status >= 500) {
                        log.warn(line, args);
                    } else {
                        log.info(line, args);
                    }
                } else if (durationMs >= slowRequestMs) {
                    log.warn("event=http_request_slow method={} path={} status={} durationMs={} thresholdMs={} {}",
                            method, path, status, durationMs, slowRequestMs, usage.toLogFields());
                }
            }
            MDC.clear();
        }
    }

    /** {@code success} (2xx/3xx), {@code rejected} (4xx: the caller's mistake or a business rule), {@code failed} (5xx). */
    static String outcomeOf(int status) {
        if (status >= 500) {
            return "failed";
        }
        return status >= 400 ? "rejected" : "success";
    }

    static String resolveRequestId(String inbound) {
        if (inbound != null && VALID_INBOUND_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Health checks, API docs and CORS preflights would drown out real traffic. */
    private boolean isQuiet(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }
}
