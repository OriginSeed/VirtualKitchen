package com.processVisualisation.virtualKitchen.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Caps the size of every request body at {@code app.http.max-body-bytes} (default 5 MB). JSON bodies are otherwise
 * unbounded, so one oversized request could tie up memory and a worker thread.
 * <p>
 * The limit is enforced while the body is read, not up front: the request continues into the security chain (so a
 * rejection still carries CORS headers the browser can read) and the read that crosses the limit throws
 * {@link PayloadTooLargeException}, which {@link GlobalExceptionHandler} answers with 413 and
 * {@link ErrorCode#PAYLOAD_TOO_LARGE}. A {@code Content-Length} already over the limit fails on the first read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    static final String MESSAGE = "That request is too large. Please make it smaller and try again.";

    private final long maxBytes;

    public RequestSizeLimitFilter(@Value("${app.http.max-body-bytes:5242880}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    /** Thrown from a body read once more than the allowed bytes have been read. */
    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(long maxBytes) {
            super("Request body exceeds " + maxBytes + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), getContentLengthLong(), maxBytes);
            }
            return stream;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8";
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), encoding));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long declaredLength;
        private final long maxBytes;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long declaredLength, long maxBytes) {
            this.delegate = delegate;
            this.declaredLength = declaredLength;
            this.maxBytes = maxBytes;
        }

        private void check(long more) throws PayloadTooLargeException {
            if (declaredLength > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            read += more;
            if (read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public int read() throws IOException {
            check(0);
            int b = delegate.read();
            if (b != -1) {
                check(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            check(0);
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                check(n);
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
