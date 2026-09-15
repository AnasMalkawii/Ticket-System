package com.ticketsystem.shared.api;

import com.ticketsystem.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces the JSON/request body budget for both known-length and streamed bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final ApiProperties properties;
    private final ApiProblemWriter problemWriter;

    public RequestBodyLimitFilter(ApiProperties properties, ApiProblemWriter problemWriter) {
        this.properties = properties;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        long maximumBytes = properties.getMaxRequestBodyBytes().toBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maximumBytes) {
            problemWriter.write(response, ErrorCode.VALIDATION_ERROR,
                    "Request body must not exceed " + maximumBytes + " bytes.");
            return;
        }

        try {
            filterChain.doFilter(new LimitedBodyRequest(request, maximumBytes), response);
        } catch (RequestBodyTooLargeException tooLarge) {
            problemWriter.write(response, ErrorCode.VALIDATION_ERROR, tooLarge.getMessage());
        }
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long maximumBytes;
        private ServletInputStream inputStream;

        private LimitedBodyRequest(HttpServletRequest request, long maximumBytes) {
            super(request);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new LimitedServletInputStream(super.getInputStream(), maximumBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maximumBytes;
        private long consumed;

        private LimitedServletInputStream(ServletInputStream delegate, long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                recordRead(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long remainingPlusOne = maximumBytes - consumed + 1;
            int boundedLength = (int) Math.min(length, Math.max(1, remainingPlusOne));
            int read = delegate.read(bytes, offset, boundedLength);
            if (read > 0) {
                recordRead(read);
            }
            return read;
        }

        private void recordRead(int count) throws RequestBodyTooLargeException {
            consumed += count;
            if (consumed > maximumBytes) {
                throw new RequestBodyTooLargeException(maximumBytes);
            }
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
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
