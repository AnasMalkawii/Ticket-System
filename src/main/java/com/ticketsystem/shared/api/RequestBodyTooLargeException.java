package com.ticketsystem.shared.api;

import java.io.IOException;

/** Raised while consuming a streamed or chunked request body that crosses the API limit. */
public class RequestBodyTooLargeException extends IOException {

    private final long maximumBytes;

    public RequestBodyTooLargeException(long maximumBytes) {
        super("Request body must not exceed " + maximumBytes + " bytes.");
        this.maximumBytes = maximumBytes;
    }

    public long getMaximumBytes() {
        return maximumBytes;
    }
}
