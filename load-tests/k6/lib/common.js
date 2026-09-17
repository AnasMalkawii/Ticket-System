import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Business rejections are correct HTTP outcomes, not transport failures. Every unexpected
// response is still counted explicitly below by its stable API error code.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

export const reserveLatency = new Trend('reserve_latency', true);
export const cancelLatency = new Trend('cancel_latency', true);
export const reservationsAccepted = new Counter('reservations_accepted');
export const reservationsCreated = new Counter('reservations_created');
export const reservationsReplayed = new Counter('reservations_replayed');
export const cancellationsSucceeded = new Counter('cancellations_succeeded');
export const errorsSoldOut = new Counter('errors_sold_out');
export const errorsUserLimit = new Counter('errors_user_limit');
export const errorsIdempotencyInProgress = new Counter('errors_idempotency_in_progress');
export const errorsRateLimited = new Counter('errors_rate_limited');
export const serverFaults = new Counter('reservation_server_faults');
export const unexpectedResponses = new Counter('reservation_unexpected_responses');

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const runId = __ENV.RUN_ID || 'manual';
const userTokens = {};

export function initializeOutcomeMetrics() {
  // A zero sample keeps exact-count thresholds evaluable even when the correct count is 0.
  reservationsAccepted.add(0);
  reservationsCreated.add(0);
  reservationsReplayed.add(0);
  cancellationsSucceeded.add(0);
  errorsSoldOut.add(0);
  errorsUserLimit.add(0);
  errorsIdempotencyInProgress.add(0);
  errorsRateLimited.add(0);
  serverFaults.add(0);
  unexpectedResponses.add(0);
}

export function createOpenEvent(scenarioName, totalTickets) {
  const adminUsername = __ENV.ADMIN_USERNAME || 'load-admin';
  const adminPassword = __ENV.ADMIN_PASSWORD || 'password';
  const login = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    username: adminUsername,
    password: adminPassword,
  }), jsonParams(null, 'setup-login'));
  if (login.status !== 200) {
    fail(`admin login failed: HTTP ${login.status} ${login.body}`);
  }

  const adminToken = login.json('accessToken');
  const now = Date.now();
  const suiteName = __ENV.SUITE_NAME || 'day6';
  const eventName = `${suiteName}-${scenarioName}-${runId}`;
  const created = http.post(`${baseUrl}/api/v1/admin/events`, JSON.stringify({
    name: eventName,
    venue: 'Day 6 Load Lab',
    startsAt: new Date(now + 2 * 60 * 60 * 1000).toISOString(),
    saleStartsAt: new Date(now - 5 * 60 * 1000).toISOString(),
    saleEndsAt: new Date(now + 60 * 60 * 1000).toISOString(),
    totalTickets,
    priceMinor: 5000,
    currency: 'USD',
  }), jsonParams(adminToken, 'setup-create-event'));
  if (created.status !== 201) {
    fail(`event creation failed: HTTP ${created.status} ${created.body}`);
  }

  const eventId = created.json('id');
  const opened = http.patch(`${baseUrl}/api/v1/admin/events/${eventId}`,
    JSON.stringify({ status: 'ON_SALE' }), jsonParams(adminToken, 'setup-open-event'));
  if (opened.status !== 200) {
    fail(`event activation failed: HTTP ${opened.status} ${opened.body}`);
  }
  return { eventId, eventName };
}

export function reserve(eventId, userId, idempotencyKey, quantity = 1) {
  const response = http.post(`${baseUrl}/api/v1/events/${eventId}/reservations`,
    JSON.stringify({ quantity }), {
      headers: {
        Authorization: `Bearer ${userToken(userId)}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      // Keep dynamic event IDs out of k6's URL metric key during long soaks.
      tags: { operation: 'reserve', name: 'POST /api/v1/events/:eventId/reservations' },
      timeout: '5s',
    });
  reserveLatency.add(response.timings.duration);

  const body = parseBody(response);
  if (response.status === 201) {
    reservationsAccepted.add(1);
    if (response.headers['Idempotency-Replayed'] === 'true') {
      reservationsReplayed.add(1);
    } else {
      reservationsCreated.add(1);
    }
    check(response, {
      'reservation response has an id': () => Boolean(body && body.id),
      'reservation response is pending': () => body && body.status === 'PENDING',
    });
    return body;
  }

  recordError(response, body);
  return null;
}

export function cancel(reservationId, userId) {
  const response = http.del(`${baseUrl}/api/v1/reservations/${reservationId}`, null, {
    headers: { Authorization: `Bearer ${userToken(userId)}` },
    // Every reservation ID is unique; grouping prevents an unbounded metric series count.
    tags: { operation: 'cancel', name: 'DELETE /api/v1/reservations/:reservationId' },
    timeout: '5s',
  });
  cancelLatency.add(response.timings.duration);
  const body = parseBody(response);
  if (response.status === 200 && body && body.status === 'CANCELLED') {
    cancellationsSucceeded.add(1);
    return body;
  }
  recordError(response, body);
  return null;
}

export function primeReservation(eventId, userNumber, key, quantity) {
  const response = http.post(`${baseUrl}/api/v1/events/${eventId}/reservations`,
    JSON.stringify({ quantity }), {
      headers: {
        Authorization: `Bearer ${userToken(userId(userNumber))}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
      },
      tags: { operation: 'setup-prime', name: 'POST /api/v1/events/:eventId/reservations' },
      timeout: '5s',
    });
  if (response.status !== 201) {
    fail(`sold-out priming failed: HTTP ${response.status} ${response.body}`);
  }
}

export function userId(number) {
  const suffix = String(number).padStart(12, '0').slice(-12);
  return `00000000-0000-4000-8000-${suffix}`;
}

export function scenarioKey(scenarioName, vuNumber) {
  return `${scenarioName}-${runId}-${vuNumber}`;
}

function userToken(subject) {
  if (userTokens[subject]) {
    return userTokens[subject];
  }
  const username = `load-${subject.replaceAll('-', '')}`;
  const password = 'load-only-user-password';
  const registration = http.post(`${baseUrl}/api/v1/auth/register`, JSON.stringify({
    username,
    password,
  }), jsonParams(null, 'setup-register-user'));
  if (registration.status !== 201 && registration.status !== 409) {
    fail(`user registration failed: HTTP ${registration.status} ${registration.body}`);
  }
  const login = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    username,
    password,
  }), jsonParams(null, 'setup-login-user'));
  if (login.status !== 200) {
    fail(`user login failed: HTTP ${login.status} ${login.body}`);
  }
  userTokens[subject] = login.json('accessToken');
  return userTokens[subject];
}

function jsonParams(token, operation) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { headers, tags: { operation }, timeout: '5s' };
}

function parseBody(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function recordError(response, body) {
  const code = body && body.code ? body.code : 'NO_DOMAIN_CODE';
  if (code === 'SOLD_OUT') {
    errorsSoldOut.add(1);
  } else if (code === 'USER_LIMIT_EXCEEDED') {
    errorsUserLimit.add(1);
  } else if (code === 'IDEMPOTENCY_IN_PROGRESS') {
    errorsIdempotencyInProgress.add(1);
  } else if (code === 'RATE_LIMITED') {
    errorsRateLimited.add(1);
  } else if (response.status >= 500 || response.status === 0) {
    serverFaults.add(1);
  } else {
    unexpectedResponses.add(1);
  }
}
