package com.processVisualisation.virtualKitchen.common.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Static guard rails for the logging policy, checked against the main sources. */
class LoggingHygieneTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    @Test
    void applicationCodeLogsThroughSlf4jNotSystemOut() throws IOException {
        assertThat(sourcesContaining("System.out.print", "System.err.print", "printStackTrace()")).isEmpty();
    }

    @Test
    void noConnectionStringWithEmbeddedCredentials() throws IOException {
        java.util.regex.Pattern credentialUri = java.util.regex.Pattern.compile("mongodb(\\+srv)?://[^\\s\"/@:]+:[^\\s\"@]+@");
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return credentialUri.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }

    /** A log call whose message is a string literal: group 3 is the literal. */
    private static final java.util.regex.Pattern LITERAL_LOG_CALL = java.util.regex.Pattern.compile(
            "\\b(log|logger)\\.(trace|debug|info|warn|error)\\(\\s*\"([^\"]*)\"");

    @Test
    void applicationLogLinesAreEventsWithKeyValueFields() throws IOException {
        // FailureLogger builds its "event=" prefix itself.
        List<String> offenders = matchesIn(LITERAL_LOG_CALL, "FailureLogger.java").stream()
                .filter(m -> !m.group(3).startsWith("event="))
                .map(m -> m.group(0))
                .toList();
        assertThat(offenders).as("log messages start with event=<name>; see docs/logging.md").isEmpty();
    }

    @Test
    void personalDataAndPromptsNeverGoIntoLogCalls() throws IOException {
        java.util.regex.Pattern leak = java.util.regex.Pattern.compile(
                "\\b(log|logger)\\.(trace|debug|info|warn|error)\\([^;]*\\.(getEmail|getPassword|getUserPrompt|getRecipeText|getOtp)\\(\\)");
        assertThat(matchesIn(leak).stream().map(m -> m.group(0)).toList()).isEmpty();
    }

    private static List<java.util.regex.MatchResult> matchesIn(java.util.regex.Pattern pattern, String... skipFiles)
            throws IOException {
        List<String> skip = List.of(skipFiles);
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !skip.contains(p.getFileName().toString()))
                    .flatMap(p -> {
                        try {
                            return pattern.matcher(Files.readString(p)).results();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }

    private static List<Path> sourcesContaining(String... needles) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String source = Files.readString(p);
                            for (String needle : needles) {
                                if (source.contains(needle)) {
                                    return true;
                                }
                            }
                            return false;
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }
}
