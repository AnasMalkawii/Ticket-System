import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import {
  createOpenEvent,
  initializeOutcomeMetrics,
  reserve,
  scenarioKey,
  userId,
} from './lib/common.js';

const duration = __ENV.DRILL_DURATION || '8m';
const virtualUsers = Number.parseInt(__ENV.VUS || '30', 10);
const maximumAttempts = Number.parseInt(__ENV.MAXIMUM_ATTEMPTS || '30', 10);

const logicalStarted = new Counter('logical_reservations_started');
const logicalSucceeded = new Counter('logical_reservations_succeeded');
const logicalRecovered = new Counter('logical_reservations_recovered');
const logicalFailed = new Counter('logical_reservations_failed');
const retryAttempts = new Counter('reservation_retry_attempts');
const recoveryLatency = new Trend('logical_recovery_latency', true);

export const options = {
  scenarios: {
    failure_drill: {
      executor: 'constant-vus',
      vus: virtualUsers,
      duration,
      gracefulStop: '45s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    reservation_unexpected_responses: ['count==0'],
    errors_sold_out: ['count==0'],
    errors_user_limit: ['count==0'],
  },
};

export function setup() {
  initializeOutcomeMetrics();
  logicalStarted.add(0);
  logicalSucceeded.add(0);
  logicalRecovered.add(0);
  logicalFailed.add(0);
  retryAttempts.add(0);
  return createOpenEvent('failure-drill', 500000);
}

export default function (data) {
  const vu = exec.vu.idInTest;
  const iteration = exec.vu.iterationInScenario;
  // Each logical operation gets a different user, while its retries retain that identity
  // and idempotency key. A response lost around COMMIT can therefore be resolved safely.
  const identity = vu * 1000000 + iteration;
  const user = userId(identity);
  const key = scenarioKey(`resilience-${vu}`, iteration);
  const startedAt = Date.now();
  let reservation = null;
  let attempts = 0;

  logicalStarted.add(1);
  while (!reservation && attempts < maximumAttempts) {
    attempts += 1;
    reservation = reserve(data.eventId, user, key, 1);
    if (!reservation && attempts < maximumAttempts) {
      retryAttempts.add(1);
      sleep(0.5);
    }
  }

  if (reservation) {
    logicalSucceeded.add(1);
    if (attempts > 1) {
      logicalRecovered.add(1);
      recoveryLatency.add(Date.now() - startedAt);
    }
  } else {
    logicalFailed.add(1);
  }
  sleep(0.1);
}
