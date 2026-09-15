import exec from 'k6/execution';
import {
  createOpenEvent,
  initializeOutcomeMetrics,
  reserve,
  scenarioKey,
  userId,
} from './lib/common.js';

export const options = {
  scenarios: {
    baseline_500_vus: {
      executor: 'per-vu-iterations',
      vus: 500,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    reservations_created: ['count==100'],
    errors_sold_out: ['count==400'],
    reservation_server_faults: ['count==0'],
    reservation_unexpected_responses: ['count==0'],
    reserve_latency: ['p(95)<=300', 'p(99)<=800'],
  },
};

export function setup() {
  initializeOutcomeMetrics();
  return createOpenEvent('baseline', 100);
}

export default function (data) {
  const number = exec.vu.idInTest;
  reserve(data.eventId, userId(number), scenarioKey('baseline', number), 1);
}
