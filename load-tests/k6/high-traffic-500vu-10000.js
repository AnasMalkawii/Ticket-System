import exec from 'k6/execution';
import {
  createOpenEvent,
  initializeOutcomeMetrics,
  reserve,
  userId,
} from './lib/common.js';

const virtualUsers = Number.parseInt(__ENV.VUS || '500', 10);
const reservationRequests = Number.parseInt(__ENV.REQUESTS || '10000', 10);

if (!Number.isInteger(virtualUsers) || virtualUsers < 1) {
  throw new Error('VUS must be a positive integer');
}
if (!Number.isInteger(reservationRequests) || reservationRequests < 1) {
  throw new Error('REQUESTS must be a positive integer');
}

export const options = {
  scenarios: {
    high_traffic_booking: {
      executor: 'shared-iterations',
      vus: virtualUsers,
      iterations: reservationRequests,
      maxDuration: '10m',
      gracefulStop: '30s',
    },
  },
};

export function setup() {
  initializeOutcomeMetrics();
  return createOpenEvent('high-traffic-hot-row', reservationRequests);
}

export default function (data) {
  const requestNumber = exec.scenario.iterationInTest + 1;
  reserve(
    data.eventId,
    userId(requestNumber),
    'high-traffic-' + (__ENV.RUN_ID || 'manual') + '-' + requestNumber,
    1,
  );
}
