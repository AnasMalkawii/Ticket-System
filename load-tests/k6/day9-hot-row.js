import exec from 'k6/execution';
import { sleep } from 'k6';
import {
  createOpenEvent,
  initializeOutcomeMetrics,
  reserve,
  scenarioKey,
  userId,
} from './lib/common.js';

const virtualUsers = Number.parseInt(__ENV.VUS || '500', 10);
const arrivalSpreadSeconds = Number.parseFloat(__ENV.ARRIVAL_SPREAD_SECONDS || '2');

export const options = {
  scenarios: {
    day9_hot_row: {
      executor: 'per-vu-iterations',
      vus: virtualUsers,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    reservation_unexpected_responses: ['count==0'],
  },
};

export function setup() {
  initializeOutcomeMetrics();
  return createOpenEvent('hot-row', 100);
}

export default function (data) {
  const number = exec.vu.idInTest;
  // Keep all requested VUs alive while avoiding a single-millisecond SYN burst through
  // Docker Desktop's Windows port proxy. The two-second spread is still far faster than
  // the serialized hot-row path and therefore maintains sustained database contention.
  sleep(((number - 1) / virtualUsers) * arrivalSpreadSeconds);
  reserve(data.eventId, userId(number), scenarioKey('hot-row', number), 1);
}
