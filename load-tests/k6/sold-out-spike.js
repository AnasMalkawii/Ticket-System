import exec from 'k6/execution';
import {
  createOpenEvent,
  initializeOutcomeMetrics,
  primeReservation,
  reserve,
  scenarioKey,
  userId,
} from './lib/common.js';

export const options = {
  scenarios: {
    sold_out_spike: {
      executor: 'per-vu-iterations',
      vus: 500,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    reservations_created: ['count==0'],
    errors_sold_out: ['count==500'],
    reservation_server_faults: ['count==0'],
    reservation_unexpected_responses: ['count==0'],
    reserve_latency: ['p(95)<=300', 'p(99)<=800'],
  },
};

export function setup() {
  initializeOutcomeMetrics();
  const event = createOpenEvent('sold-out-spike', 5);
  primeReservation(event.eventId, 900001, scenarioKey('prime', 1), 4);
  primeReservation(event.eventId, 900002, scenarioKey('prime', 2), 1);
  return event;
}

export default function (data) {
  const number = exec.vu.idInTest;
  reserve(data.eventId, userId(200000 + number), scenarioKey('soldout', number), 1);
}
