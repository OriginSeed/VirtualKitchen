package com.processVisualisation.virtualKitchen.common.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserFacingErrorsTest {

    @Test
    void ownWordingPassesThrough() {
        assertThat(UserFacingErrors.forJob("This recipe has too many steps for a storyboard."))
                .isEqualTo("This recipe has too many steps for a storyboard.");
    }

    @Test
    void noFailureStaysNull() {
        assertThat(UserFacingErrors.forJob(null)).isNull();
    }

    @Test
    void technicalTextBecomesGeneric() {
        assertThat(UserFacingErrors.forJob("AiCommunicationException: Gemini returned HTTP 500 {\"error\":\"internal\"}"))
                .isEqualTo(UserFacingErrors.GENERIC);
        assertThat(UserFacingErrors.forJob("NullPointerException")).isEqualTo(UserFacingErrors.GENERIC);
        assertThat(UserFacingErrors.forJob("Upload to https://x.supabase.co failed")).isEqualTo(UserFacingErrors.GENERIC);
        assertThat(UserFacingErrors.forJob("x".repeat(400))).isEqualTo(UserFacingErrors.GENERIC);
    }

    @Test
    void timeoutsAndThrottlingGetTheirOwnMessages() {
        assertThat(UserFacingErrors.forJob("AiTimeoutException: request timed out after 120000 ms"))
                .isEqualTo(UserFacingErrors.TIMEOUT);
        assertThat(UserFacingErrors.forJob("Provider answered 429 Too Many Requests")).isEqualTo(UserFacingErrors.BUSY);
        assertThat(UserFacingErrors.forJob("You exceeded your current quota")).isEqualTo(UserFacingErrors.BUSY);
    }
}
