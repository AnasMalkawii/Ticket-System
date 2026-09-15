import {
  createOpenEvent,
  initializeOutcomeMetrics,
  reserve,
  scenarioKey,
  userId,
} from './lib/common.js';

export const options = {
  scenarios: {
    duplicate_retries: {
      executor: 'per-vu-iterations',
      vus: 25,
      iterations: 1,
      maxDuration: '1m',
    },
  },
  thresholds: {
    reservations_accepted: ['count==25'],
    reservations_created: ['count==1'],
    reservations_replayed: ['count==24'],
    reservation_server_faults: ['count==0'],
    reservation_unexpected_responses: ['count==0'],
  },
};

export function setup() {
  initializeOutcomeMetrics();
  return createOpenEvent('duplicate-retries', 100);
}

export default function (data) {
  reserve(data.eventId, userId(700001), scenarioKey('duplicate', 1), 1);
}
