package com.ticketsystem.auth.entity;

import com.ticketsystem.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Rotating, server-revocable refresh-token session. Only token hashes are persisted. */
@Entity
@Table(name = "auth_session")
public class RefreshToken {

    @Id
    private UUID id;
    @Column(name = "family_id", nullable = false)
    private UUID familyId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;
    @Column(name = "csrf_hash", nullable = false, length = 64)
    private String csrfHash;
    @Column(name = "client_ip", length = 45)
    private String clientIp;
    @Column(name = "user_agent", length = 512)
    private String userAgent;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "replaced_by")
    private UUID replacedBy;
    @Version
    @Column(nullable = false)
    private long version;

    protected RefreshToken() {
        // for JPA
    }

    public static RefreshToken create(UUID id, User user, UUID familyId, String tokenHash,
                                     String csrfHash, String clientIp, String userAgent,
                                     Instant now, Instant expiresAt) {
        RefreshToken session = new RefreshToken();
        session.id = id;
        session.familyId = familyId;
        session.user = user;
        session.tokenHash = tokenHash;
        session.csrfHash = csrfHash;
        session.clientIp = truncate(clientIp, 45);
        session.userAgent = truncate(userAgent, 512);
        session.createdAt = now;
        session.expiresAt = expiresAt;
        return session;
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt) && user.isEnabled();
    }

    public void rotateTo(UUID replacementId, Instant now) {
        revokedAt = now;
        replacedBy = replacementId;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public User getUser() {
        return user;
    }

    public String getCsrfHash() {
        return csrfHash;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
