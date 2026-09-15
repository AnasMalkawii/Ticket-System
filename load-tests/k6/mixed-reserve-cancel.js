import exec from 'k6/execution';
import {
  cancel,
  createOpenEvent,
  initializeOutcomeMetrics,
  reserve,
  scenarioKey,
  userId,
} from './lib/common.js';

export const options = {
  scenarios: {
    mixed_reserve_cancel: {
      executor: 'per-vu-iterations',
      vus: 100,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    reservations_created: ['count==100'],
    cancellations_succeeded: ['count==50'],
    reservation_server_faults: ['count==0'],
    reservation_unexpected_responses: ['count==0'],
    reserve_latency: ['p(95)<=300', 'p(99)<=800'],
    cancel_latency: ['p(95)<=250', 'p(99)<=700'],
  },
};

export function setup() {
  initializeOutcomeMetrics();
  return createOpenEvent('mixed-reserve-cancel', 100);
}

export default function (data) {
  const number = exec.vu.idInTest;
  const subject = userId(100000 + number);
  const reservation = reserve(
    data.eventId, subject, scenarioKey('mixed', number), 1);
  if (reservation && number % 2 === 0) {
    cancel(reservation.id, subject);
  }
}
