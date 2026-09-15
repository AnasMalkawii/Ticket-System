import exec from 'k6/execution';
import { sleep } from 'k6';
import {
  cancel,
  createOpenEvent,
  initializeOutcomeMetrics,
  reserve,
  scenarioKey,
  userId,
} from './lib/common.js';

const duration = __ENV.SOAK_DURATION || '30m';
const virtualUsers = Number.parseInt(__ENV.VUS || '10', 10);

export const options = {
  scenarios: {
    steady_reserve_cancel: {
      executor: 'constant-vus',
      vus: virtualUsers,
      duration,
      gracefulStop: '30s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    reservation_server_faults: ['count==0'],
    reservation_unexpected_responses: ['count==0'],
  },
};

export function setup() {
  initializeOutcomeMetrics();
  return createOpenEvent('soak-cycle', 100);
}

export default function (data) {
  const vu = exec.vu.idInTest;
  const iteration = exec.vu.iterationInScenario;
  const user = userId(vu);
  const key = scenarioKey(`soak-${vu}`, iteration);
  const reservation = reserve(data.eventId, user, key, 1);

  if (reservation) {
    // Every tenth cycle retries the original POST before cancelling it. This keeps
    // duplicate-key replay active throughout the soak without consuming extra inventory.
    if (iteration % 10 === 0) {
      reserve(data.eventId, user, key, 1);
    }
    cancel(reservation.id, user);
  }

  sleep(0.15);
}
