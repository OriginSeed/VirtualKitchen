package com.processVisualisation.virtualKitchen.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    /** auth 3/min, AI start 2/min, general 5/min. */
    private RateLimitFilter filter(boolean trustForwarded) {
        return new RateLimitFilter(true, trustForwarded, 3, 2, 5, now::get);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse send(RateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        return request;
    }

    private static void signIn(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void requestsOverTheLimitGet429WithRetryAfterAndACode() throws Exception {
        RateLimitFilter filter = filter(false);
        for (int i = 0; i < 3; i++) {
            assertThat(send(filter, request("POST", "/api/v1/auth/login", "1.1.1.1")).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = send(filter, request("POST", "/api/v1/auth/login", "1.1.1.1"));

        assertThat(blocked.getStatus()).isEqualTo(429);
        // 3 per minute: one token every 20 s.
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("20");
        assertThat(blocked.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").contains("\"status\":429");
    }

    @Test
    void tokensRefillOverTime() throws Exception {
        RateLimitFilter filter = filter(false);
        for (int i = 0; i < 3; i++) {
            send(filter, request("POST", "/api/v1/auth/login", "1.1.1.1"));
        }
        assertThat(send(filter, request("POST", "/api/v1/auth/login", "1.1.1.1")).getStatus()).isEqualTo(429);

        now.addAndGet(20_000);

        assertThat(send(filter, request("POST", "/api/v1/auth/login", "1.1.1.1")).getStatus()).isEqualTo(200);
    }

    @Test
    void signedInUsersAreLimitedSeparatelyFromEachOther() throws Exception {
        RateLimitFilter filter = filter(false);
        signIn("7");
        for (int i = 0; i < 2; i++) {
            assertThat(send(filter, request("POST", "/api/v1/recipes/1/storyboard/jobs", "1.1.1.1")).getStatus()).isEqualTo(200);
        }
        assertThat(send(filter, request("POST", "/api/v1/recipes/1/storyboard/jobs", "1.1.1.1")).getStatus()).isEqualTo(429);

        // Same address, different user: their own bucket.
        signIn("8");
        assertThat(send(filter, request("POST", "/api/v1/recipes/1/storyboard/jobs", "1.1.1.1")).getStatus()).isEqualTo(200);
    }

    @Test
    void aiStartLimitDoesNotBlockOrdinaryRequests() throws Exception {
        RateLimitFilter filter = filter(false);
        signIn("7");
        send(filter, request("POST", "/api/v1/recipes/1/processes/generate/jobs", "1.1.1.1"));
        send(filter, request("POST", "/api/v1/recipes/1/processes/generate/jobs", "1.1.1.1"));
        assertThat(send(filter, request("POST", "/api/v1/recipes/1/processes/generate/jobs", "1.1.1.1")).getStatus()).isEqualTo(429);

        assertThat(send(filter, request("GET", "/api/v1/recipes/1/processes/generate/jobs/abc", "1.1.1.1")).getStatus()).isEqualTo(200);
    }

    @Test
    void tiersAreChosenByMethodAndPath() {
        assertThat(RateLimitFilter.tierOf(request("POST", "/api/v1/auth/login", "x"))).isEqualTo(RateLimitFilter.Tier.AUTH);
        assertThat(RateLimitFilter.tierOf(request("POST", "/api/v1/auth/me", "x"))).isEqualTo(RateLimitFilter.Tier.GENERAL);
        assertThat(RateLimitFilter.tierOf(request("POST", "/api/v1/recipes/5/ai-workflows", "x"))).isEqualTo(RateLimitFilter.Tier.AI_START);
        assertThat(RateLimitFilter.tierOf(request("POST", "/api/v1/recipes/5/ai-workflows/w1/approve", "x"))).isEqualTo(RateLimitFilter.Tier.GENERAL);
        assertThat(RateLimitFilter.tierOf(request("POST", "/api/v1/recipes/5/processes/9/steps/s1/narration", "x"))).isEqualTo(RateLimitFilter.Tier.GENERAL);
        assertThat(RateLimitFilter.tierOf(request("GET", "/api/v1/recipes/5/storyboard/jobs", "x"))).isEqualTo(RateLimitFilter.Tier.GENERAL);
    }

    @Test
    void forwardedForIsIgnoredUnlessTrusted() throws Exception {
        RateLimitFilter untrusted = filter(false);
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = request("POST", "/api/v1/auth/login", "1.1.1.1");
            r.addHeader("X-Forwarded-For", "9.9.9." + i);
            send(untrusted, r);
        }
        MockHttpServletRequest spoofed = request("POST", "/api/v1/auth/login", "1.1.1.1");
        spoofed.addHeader("X-Forwarded-For", "9.9.9.99");
        assertThat(send(untrusted, spoofed).getStatus()).isEqualTo(429);

        RateLimitFilter trusted = filter(true);
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = request("POST", "/api/v1/auth/login", "10.0.0.1");
            r.addHeader("X-Forwarded-For", "2.2.2.2, 10.0.0.1");
            send(trusted, r);
        }
        MockHttpServletRequest other = request("POST", "/api/v1/auth/login", "10.0.0.1");
        other.addHeader("X-Forwarded-For", "3.3.3.3");
        assertThat(send(trusted, other).getStatus()).isEqualTo(200);
    }

    @Test
    void preflightsErrorsAndNarrationAudioAreNeverLimited() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, false, 1, 1, 1, now::get);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, request("OPTIONS", "/api/v1/recipes", "1.1.1.1")).getStatus()).isEqualTo(200);
            assertThat(send(filter, request("GET", "/api/v1/narration-audio/a.wav", "1.1.1.1")).getStatus()).isEqualTo(200);
            assertThat(send(filter, request("GET", "/error", "1.1.1.1")).getStatus()).isEqualTo(200);
        }
        assertThat(filter.bucketCount()).isZero();
    }

    @Test
    void disabledFilterLetsEverythingThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(false, false, 1, 1, 1, now::get);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, request("POST", "/api/v1/auth/login", "1.1.1.1")).getStatus()).isEqualTo(200);
        }
    }
}
