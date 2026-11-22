package com.processVisualisation.virtualKitchen.common.web;

import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;

import java.time.LocalDateTime;

/**
 * Standard error payload returned to clients whenever a request fails: when it happened, the HTTP status, a short
 * error label, a message safe to show a user (never an exception's internals), a stable {@link ErrorCode} the
 * frontend maps to its own wording, and the request id to quote to support.
 */
@Data
@AllArgsConstructor
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String code;
    private String requestId;

    /** An error response for the current request, stamped now, with the request id from the MDC. */
    public static ErrorResponse of(HttpStatusCode status, String error, String message, ErrorCode code) {
        return new ErrorResponse(LocalDateTime.now(), status.value(), error, message, code.name(),
                MDC.get(MdcKeys.REQUEST_ID));
    }

    /**
     * This response as JSON, for servlet filters that answer before Spring MVC (and its message converters) run.
     * Field names and values match what MVC serializes for this class.
     */
    public String toJson() {
        return "{\"timestamp\":" + quote(timestamp == null ? null : timestamp.toString())
                + ",\"status\":" + status
                + ",\"error\":" + quote(error)
                + ",\"message\":" + quote(message)
                + ",\"code\":" + quote(code)
                + ",\"requestId\":" + quote(requestId) + "}";
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
