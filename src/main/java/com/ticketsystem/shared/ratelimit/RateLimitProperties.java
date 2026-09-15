package com.ticketsystem.shared.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Distributed reserve-attempt limits; Redis owns the shared counters. */
@ConfigurationProperties(prefix = "ticketing.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Duration window = Duration.ofMinutes(1);
    private int perUser = 10;
    private int perIp = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        if (window == null || window.isZero() || window.isNegative()
                || window.toMillis() < 1) {
            throw new IllegalArgumentException("rate-limit window must be at least 1ms");
        }
        this.window = window;
    }

    public int getPerUser() {
        return perUser;
    }

    public void setPerUser(int perUser) {
        if (perUser < 1) {
            throw new IllegalArgumentException("rate-limit per-user must be positive");
        }
        this.perUser = perUser;
    }

    public int getPerIp() {
        return perIp;
    }

    public void setPerIp(int perIp) {
        if (perIp < 1) {
            throw new IllegalArgumentException("rate-limit per-ip must be positive");
        }
        this.perIp = perIp;
    }
}
