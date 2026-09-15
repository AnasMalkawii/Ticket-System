package com.ticketsystem.shared.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Boundary limits applied before a request reaches authentication or a controller. */
@ConfigurationProperties(prefix = "ticketing.api")
public class ApiProperties {

    private DataSize maxRequestBodyBytes = DataSize.ofKilobytes(16);
    private int maxRequestIdLength = 128;

    public DataSize getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public void setMaxRequestBodyBytes(DataSize maxRequestBodyBytes) {
        if (maxRequestBodyBytes == null || maxRequestBodyBytes.toBytes() < 1) {
            throw new IllegalArgumentException("max-request-body-bytes must be positive");
        }
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    public int getMaxRequestIdLength() {
        return maxRequestIdLength;
    }

    public void setMaxRequestIdLength(int maxRequestIdLength) {
        if (maxRequestIdLength < 1) {
            throw new IllegalArgumentException("max-request-id-length must be positive");
        }
        this.maxRequestIdLength = maxRequestIdLength;
    }
}
