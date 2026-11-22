package com.processVisualisation.virtualKitchen.common.web;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the failure text stored on an AI job (often an exception's class and message, sometimes a provider's own
 * error body) into a message fit to show a user. Jobs keep the raw text in the database and logs for diagnosis
 * (admin analytics reads it there); only the user-facing status responses go through this.
 * <p>
 * Text that already reads like this application's own wording (short, with no exception names, status codes,
 * URLs or JSON) is passed through, since many job failures explain themselves ("This recipe has too many steps
 * for a storyboard"). Anything else becomes a plain message chosen by what kind of failure it looks like.
 */
public final class UserFacingErrors {

    public static final String GENERIC = "Something went wrong while the AI was working on this. Please try again.";
    public static final String TIMEOUT = "The AI took too long to respond. Please try again.";
    public static final String BUSY = "The AI service is busy right now. Please try again in a minute.";

    private static final int MAX_PASS_THROUGH_LENGTH = 300;

    /** Signs that text is technical: exception names, HTTP statuses, URLs, JSON, stack frames, provider names. */
    private static final Pattern TECHNICAL = Pattern.compile(
            "[A-Za-z]+(Exception|Error)\\b|\\bHTTP\\b|\\bstatus\\s*[=:]?\\s*\\d{3}\\b|https?://|[{}\\[\\]]|\\bat [a-z]+\\.|"
                    + "\\b(api[ _-]?key|token|openai|gemini|deepinfra|ollama|supabase|mongo)\\b",
            Pattern.CASE_INSENSITIVE);

    private UserFacingErrors() {
    }

    /**
     * The message to show a user for a job's stored failure text.
     *
     * @param raw the stored failure text, or null
     * @return null when there was no failure, else a user-facing message
     */
    public static String forJob(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return TIMEOUT;
        }
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("rate-limit")
                || lower.contains("too many requests") || lower.contains("busy") || lower.contains("quota")
                || lower.contains("overloaded") || lower.contains("503")) {
            return BUSY;
        }
        if (raw.length() > MAX_PASS_THROUGH_LENGTH || TECHNICAL.matcher(raw).find()) {
            return GENERIC;
        }
        return raw;
    }
}
