package com.ticketsystem.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A hold on tickets for one event by one user.
 *
 * <p><strong>There is no {@code setStatus}.</strong> The lifecycle is exposed only as
 * {@link #confirm}, {@link #cancel} and {@link #expire}, each of which consults
 * {@link ReservationStatus#canTransitionTo} and updates {@code status} and
 * {@code terminatedAt} together. That pairing is also enforced by a {@code CHECK}
 * constraint, so a half-applied transition cannot be committed even by SQL issued outside
 * this class.
 *
 * <p>These methods change reservation state only. Moving the matching inventory counters is
 * the caller's responsibility, inside the same transaction and under the inventory row lock;
 * the transactional services pair both changes under reservation and inventory row locks.
 *
 * <p>Every method takes {@code now} instead of reading a clock. Callers must pass database
 * time so that a skewed replica clock cannot expire a hold early or keep a dead one alive
 * (F-18), and so tests are deterministic.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Referenced by id, not by a JPA association: see the note on {@code Event}. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reservation() {
        // for JPA
    }

    /** Creates a new hold in {@link ReservationStatus#PENDING} with a deadline of {@code now + ttl}. */
    public static Reservation pending(UUID eventId, UUID userId, int qty, String idempotencyKey,
                                      String requestFingerprint, Duration ttl, Instant now) {
        return pendingWithId(null, eventId, userId, qty, idempotencyKey,
                requestFingerprint, ttl, now);
    }

    /** Creates a pending hold with an application-generated ID for set-based persistence. */
    public static Reservation pendingWithId(UUID id, UUID eventId, UUID userId, int qty,
                                            String idempotencyKey, String requestFingerprint,
                                            Duration ttl, Instant now) {
        if (qty <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + qty);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, was " + ttl);
        }
        Reservation reservation = new Reservation();
        reservation.id = id;
        reservation.eventId = eventId;
        reservation.userId = userId;
        reservation.qty = qty;
        reservation.status = ReservationStatus.PENDING;
        reservation.idempotencyKey = idempotencyKey;
        reservation.requestFingerprint = requestFingerprint;
        reservation.expiresAt = now.plus(ttl);
        reservation.terminatedAt = null;
        reservation.createdAt = now;
        reservation.updatedAt = now;
        return reservation;
    }

    /**
     * Confirms the hold after successful payment.
     *
     * @throws ReservationExpiredException            if the deadline has passed
     * @throws IllegalReservationTransitionException  if the hold is already terminal
     */
    public void confirm(Instant now) {
        if (isExpiredAt(now)) {
            throw new ReservationExpiredException(id, expiresAt);
        }
        transitionTo(ReservationStatus.CONFIRMED, now);
    }

    /**
     * Cancels the hold at the user's request.
     *
     * @throws IllegalReservationTransitionException if the hold is already terminal - which is
     *         exactly what the loser of a cancel-versus-expire race sees (F-06)
     */
    public void cancel(Instant now) {
        transitionTo(ReservationStatus.CANCELLED, now);
    }

    /**
     * Expires the hold. Only legal once the deadline has actually passed, so a bug in the
     * worker's query cannot release live holds.
     *
     * @throws IllegalStateException                 if the deadline has not passed
     * @throws IllegalReservationTransitionException if the hold is already terminal
     */
    public void expire(Instant now) {
        if (!isExpiredAt(now)) {
            throw new IllegalStateException(
                    "Reservation %s expires at %s and cannot be expired at %s"
                            .formatted(id, expiresAt, now));
        }
        transitionTo(ReservationStatus.EXPIRED, now);
    }

    private void transitionTo(ReservationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalReservationTransitionException(id, status, target);
        }
        this.status = target;
        this.terminatedAt = target.isTerminal() ? now : null;
        this.updatedAt = now;
    }

    /** True once the deadline has passed, regardless of whether the worker has run yet. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isActive() {
        return status.isActive();
    }

    public boolean matchesFingerprint(String candidate) {
        return requestFingerprint.equals(candidate);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getQty() {
        return qty;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getTerminatedAt() {
        return terminatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
