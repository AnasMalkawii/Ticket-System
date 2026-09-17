package com.ticketsystem.auth.service.refreshtoken;

import com.ticketsystem.auth.entity.RefreshToken;
import com.ticketsystem.auth.repository.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RefreshTokenService {
    private final RefreshTokenRepository repository;

    public RefreshTokenService(RefreshTokenRepository repository) { this.repository = repository; }

    public void createAndSaveRefreshToken(RefreshToken token) { repository.saveAndFlush(token); }

    // The owning AuthService transaction commits rejection-triggered revocation too.
    public void revokeFamily(UUID familyId, Instant now) { repository.revokeFamily(familyId, now); }
}
