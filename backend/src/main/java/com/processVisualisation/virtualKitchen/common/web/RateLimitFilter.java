package com.processVisualisation.virtualKitchen.common.web;

import com.processVisualisation.virtualKitchen.ai.recipe.narration.storage.FileSystemNarrationAudioStorage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-client request rate limiting, in memory, with three token-bucket tiers ({@code app.ratelimit.*}):
 * <ul>
 *   <li>{@link Tier#AUTH} — sign-in, sign-up, OTP and password reset ({@code POST /api/v1/auth/**}), per client
 *       address, to slow password guessing and email flooding;</li>
 *   <li>{@link Tier#AI_START} — requests that start paid AI work, per user;</li>
 *   <li>{@link Tier#GENERAL} — everything else, per user (or address when signed out); high enough for job polling.</li>
 * </ul>
 * A request over its limit gets 429 with {@code Retry-After} (seconds) and {@link ErrorCode#RATE_LIMITED}.
 * <p>
 * It runs inside the security chain right after the JWT filter (see {@code SecurityConfig}), so the signed-in user
 * is known and the response carries CORS headers; its automatic servlet registration is turned off there.
 * <p>
 * Counters live in this JVM only: with more than one backend instance each one limits separately. That is fine
 * for a single server; a shared store (e.g. Redis) would be needed beyond that.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String MESSAGE = "You're making requests too quickly. Please wait a moment and try again.";

    /** Idle buckets are dropped by a sweep that runs once per this many requests. */
    private static final int SWEEP_EVERY = 1000;
    private static final long IDLE_MS = 10 * 60_000L;

    enum Tier { AUTH, AI_START, GENERAL }

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    /** POST endpoints that start paid AI work. Per-step narration is excluded: cook mode asks for it step by step. */
    private static final List<PathPattern> AI_START_PATHS = List.of(
            PARSER.parse("/api/v1/recipes/{recipeId}/processes/generate/jobs"),
            PARSER.parse("/api/v1/recipes/{recipeId}/processes/{processId}/visualization/jobs"),
            PARSER.parse("/api/v1/recipes/{recipeId}/processes/{processId}/narrations"),
            PARSER.parse("/api/v1/recipes/{recipeId}/storyboard/jobs"),
            PARSER.parse("/api/v1/recipes/{recipeId}/storyboard/steps/{stepId}/fix"),
            PARSER.parse("/api/v1/recipes/{recipeId}/ai-workflows"),
            PARSER.parse("/api/v1/recipes/{recipeId}/ai-workflows/{workflowId}/tasks/{task}/retry"),
            PARSER.parse("/api/ai/chat"));
    private static final PathPattern AUTH_PATH = PARSER.parse("/api/v1/auth/**");
    private static final PathPattern AUTH_ME_PATH = PARSER.parse("/api/v1/auth/me");

    private final boolean enabled;
    private final boolean trustForwarded;
    private final Map<Tier, Integer> perMinute;
    private final LongSupplier clock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();

    @Autowired
    public RateLimitFilter(@Value("${app.ratelimit.enabled:true}") boolean enabled,
                           @Value("${app.ratelimit.trust-forwarded:false}") boolean trustForwarded,
                           @Value("${app.ratelimit.auth-per-minute:20}") int authPerMinute,
                           @Value("${app.ratelimit.ai-start-per-minute:10}") int aiStartPerMinute,
                           @Value("${app.ratelimit.general-per-minute:300}") int generalPerMinute) {
        this(enabled, trustForwarded, authPerMinute, aiStartPerMinute, generalPerMinute, System::currentTimeMillis);
    }

    RateLimitFilter(boolean enabled, boolean trustForwarded, int authPerMinute, int aiStartPerMinute,
                    int generalPerMinute, LongSupplier clock) {
        this.enabled = enabled;
        this.trustForwarded = trustForwarded;
        this.perMinute = Map.of(Tier.AUTH, authPerMinute, Tier.AI_START, aiStartPerMinute,
                Tier.GENERAL, generalPerMinute);
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path.equals("/error") || path.startsWith(FileSystemNarrationAudioStorage.URL_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Tier tier = tierOf(request);
        String client = tier == Tier.AUTH ? "ip:" + clientAddress(request) : clientKey(request);
        long now = clock.getAsLong();
        TokenBucket bucket = buckets.computeIfAbsent(tier + "|" + client, k -> new TokenBucket(perMinute.get(tier), now));
        long waitMs = bucket.tryConsume(now);
        sweepOccasionally(now);
        if (waitMs == 0) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, (waitMs + 999) / 1000);
        log.warn("event=rate_limited tier={} method={} path={} retryAfterSeconds={}", tier, request.getMethod(),
                request.getRequestURI(), retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", MESSAGE,
                ErrorCode.RATE_LIMITED).toJson());
    }

    static Tier tierOf(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return Tier.GENERAL;
        }
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        if (AUTH_PATH.matches(path) && !AUTH_ME_PATH.matches(path)) {
            return Tier.AUTH;
        }
        for (PathPattern pattern : AI_START_PATHS) {
            if (pattern.matches(path)) {
                return Tier.AI_START;
            }
        }
        return Tier.GENERAL;
    }

    /** The signed-in user's id, else the client address. */
    private String clientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return "user:" + auth.getName();
        }
        return "ip:" + clientAddress(request);
    }

    /**
     * The caller's address. {@code X-Forwarded-For} is trusted only when {@code app.ratelimit.trust-forwarded} says
     * a reverse proxy sets it; otherwise any client could pick its own key with that header.
     */
    private String clientAddress(HttpServletRequest request) {
        if (trustForwarded) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private void sweepOccasionally(long now) {
        if (requestCount.incrementAndGet() % SWEEP_EVERY == 0) {
            buckets.values().removeIf(b -> b.idleSince(now) > IDLE_MS);
        }
    }

    /** Number of live buckets, for tests. */
    int bucketCount() {
        return buckets.size();
    }

    /** Classic token bucket: holds up to {@code capacity} tokens, refilled evenly over a minute. */
    static final class TokenBucket {
        private final int capacity;
        private final double refillPerMs;
        private double tokens;
        private long lastRefill;

        TokenBucket(int capacity, long now) {
            this.capacity = Math.max(1, capacity);
            this.refillPerMs = this.capacity / 60_000.0;
            this.tokens = this.capacity;
            this.lastRefill = now;
        }

        /** Takes one token if available and returns 0; otherwise returns the milliseconds until one is. */
        synchronized long tryConsume(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / refillPerMs);
        }

        synchronized long idleSince(long now) {
            return now - lastRefill;
        }

        private void refill(long now) {
            if (now > lastRefill) {
                tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerMs);
                lastRefill = now;
            }
        }
    }
}
