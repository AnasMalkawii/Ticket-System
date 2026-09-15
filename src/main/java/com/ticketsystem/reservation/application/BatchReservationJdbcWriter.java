package com.ticketsystem.reservation.application;

import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.shared.persistence.TimeOrderedUuid;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Set-based persistence for one already-validated reservation batch. */
@Component
public class BatchReservationJdbcWriter {

    private static final String INSERT_RESERVATIONS = """
            INSERT INTO reservation (
                id, event_id, user_id, qty, status, idempotency_key,
                request_fingerprint, expires_at, terminated_at, created_at, updated_at)
            SELECT input.id, ?::uuid, input.user_id, input.qty, 'PENDING',
                   input.idempotency_key, input.request_fingerprint,
                   ?::timestamptz, NULL, ?::timestamptz, ?::timestamptz
            FROM unnest(?::uuid[], ?::uuid[], ?::integer[], ?::text[], ?::text[])
                 AS input(id, user_id, qty, idempotency_key, request_fingerprint)
            """;

    private static final String INSERT_OUTBOX = """
            INSERT INTO outbox_event (
                id, aggregate_type, aggregate_id, type, payload, correlation_id,
                attempts, created_at, published_at, next_attempt_at, last_error)
            SELECT input.id, 'reservation', input.aggregate_id, 'reservation.created',
                   input.payload::jsonb, input.correlation_id,
                   0, ?::timestamptz, NULL, ?::timestamptz, NULL
            FROM unnest(?::uuid[], ?::uuid[], ?::text[], ?::text[])
                 AS input(id, aggregate_id, payload, correlation_id)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BatchReservationJdbcWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public PendingReservation prepare(Reservation reservation, String correlationId) {
        return new PendingReservation(reservation, correlationId, createdPayload(reservation));
    }

    public void insert(List<PendingReservation> pending, Instant now) {
        if (pending.isEmpty()) {
            return;
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            UUID[] reservationIds = pending.stream()
                    .map(item -> item.reservation().getId()).toArray(UUID[]::new);
            UUID[] userIds = pending.stream()
                    .map(item -> item.reservation().getUserId()).toArray(UUID[]::new);
            Integer[] quantities = pending.stream()
                    .map(item -> item.reservation().getQty()).toArray(Integer[]::new);
            String[] idempotencyKeys = pending.stream()
                    .map(item -> item.reservation().getIdempotencyKey()).toArray(String[]::new);
            String[] fingerprints = pending.stream()
                    .map(item -> item.reservation().getRequestFingerprint()).toArray(String[]::new);
            UUID[] outboxIds = pending.stream()
                    .map(ignored -> TimeOrderedUuid.next()).toArray(UUID[]::new);
            String[] payloads = pending.stream()
                    .map(PendingReservation::payload).toArray(String[]::new);
            String[] correlationIds = pending.stream()
                    .map(PendingReservation::correlationId).toArray(String[]::new);

            Array reservationIdArray = connection.createArrayOf("uuid", reservationIds);
            Array userIdArray = connection.createArrayOf("uuid", userIds);
            Array quantityArray = connection.createArrayOf("integer", quantities);
            Array keyArray = connection.createArrayOf("text", idempotencyKeys);
            Array fingerprintArray = connection.createArrayOf("text", fingerprints);
            try (PreparedStatement statement = connection.prepareStatement(INSERT_RESERVATIONS)) {
                statement.setObject(1, pending.getFirst().reservation().getEventId());
                statement.setTimestamp(2, Timestamp.from(pending.getFirst().reservation().getExpiresAt()));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setTimestamp(4, Timestamp.from(now));
                statement.setArray(5, reservationIdArray);
                statement.setArray(6, userIdArray);
                statement.setArray(7, quantityArray);
                statement.setArray(8, keyArray);
                statement.setArray(9, fingerprintArray);
                requireInserted(statement.executeUpdate(), pending.size(), "reservation");
            } finally {
                reservationIdArray.free();
                userIdArray.free();
                quantityArray.free();
                keyArray.free();
                fingerprintArray.free();
            }

            Array outboxIdArray = connection.createArrayOf("uuid", outboxIds);
            Array aggregateIdArray = connection.createArrayOf("uuid", reservationIds);
            Array payloadArray = connection.createArrayOf("text", payloads);
            Array correlationArray = connection.createArrayOf("text", correlationIds);
            try (PreparedStatement statement = connection.prepareStatement(INSERT_OUTBOX)) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setArray(3, outboxIdArray);
                statement.setArray(4, aggregateIdArray);
                statement.setArray(5, payloadArray);
                statement.setArray(6, correlationArray);
                requireInserted(statement.executeUpdate(), pending.size(), "outbox");
            } finally {
                outboxIdArray.free();
                aggregateIdArray.free();
                payloadArray.free();
                correlationArray.free();
            }
            return null;
        });
    }

    private String createdPayload(Reservation reservation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservation.getId().toString());
        payload.put("eventId", reservation.getEventId().toString());
        payload.put("userId", reservation.getUserId().toString());
        payload.put("quantity", reservation.getQty());
        payload.put("status", reservation.getStatus().name());
        payload.put("expiresAt", reservation.getExpiresAt().toString());
        return objectMapper.writeValueAsString(payload);
    }

    private static void requireInserted(int actual, int expected, String table) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Expected %d %s rows but inserted %d".formatted(expected, table, actual));
        }
    }

    public record PendingReservation(Reservation reservation,
                                     String correlationId,
                                     String payload) {
    }
}
