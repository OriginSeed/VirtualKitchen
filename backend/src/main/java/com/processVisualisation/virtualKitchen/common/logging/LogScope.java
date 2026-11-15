package com.processVisualisation.virtualKitchen.common.logging;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sets MDC keys for a block of code and puts back whatever was there before when closed:
 * <pre>
 *   try (LogScope ignored = LogScope.with(MdcKeys.JOB_ID, id, MdcKeys.AREA, "system")) { ... }
 * </pre>
 * Unlike {@code MDC.putCloseable}, which removes the key on close, this restores an outer value, so scopes can nest
 * (a job started inside another job) without wiping the outer context. A null value removes the key for the scope.
 */
public final class LogScope implements AutoCloseable {

    private final Map<String, String> previous;

    private LogScope(Map<String, String> previous) {
        this.previous = previous;
    }

    /** @param keysAndValues alternating key, value pairs */
    public static LogScope with(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("LogScope.with takes key/value pairs");
        }
        Map<String, String> previous = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            String key = keysAndValues[i];
            if (!previous.containsKey(key)) {
                previous.put(key, MDC.get(key));
            }
            put(key, keysAndValues[i + 1]);
        }
        return new LogScope(previous);
    }

    @Override
    public void close() {
        previous.forEach(LogScope::put);
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
