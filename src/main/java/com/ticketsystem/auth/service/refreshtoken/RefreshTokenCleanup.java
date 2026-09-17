package com.ticketsystem.auth.service.refreshtoken;

import com.ticketsystem.auth.repository.RefreshTokenRepository;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Prevents expired server-side session records from growing without bound. */
@Component
public class RefreshTokenCleanup {

    private final RefreshTokenRepository sessions;
    private final Clock clock;

    public RefreshTokenCleanup(RefreshTokenRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ticketing.security.session-cleanup-interval:1h}")
    @Transactional
    public void deleteExpiredSessions() {
        sessions.deleteExpiredBefore(clock.instant());
    }
}
