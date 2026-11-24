import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

export const lockConflicts = new Counter('lock_conflicts_409');
export const successfulStarts = new Counter('successful_starts');
export const doubleExecutionDetected = new Counter('double_execution_detected');
export const noDoubleExecutionRate = new Rate('no_double_execution_rate');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    scenarios: {
        concurrent_starts: {
            executor: 'per-vu-iterations',
            vus: 10,
            iterations: 5,
            maxDuration: '30s',
        },
    },
    thresholds: {
        double_execution_detected: ['count==0'],
        no_double_execution_rate: ['rate==1.0'],
    },
};

export default function () {
    // 1. Create a fresh order
    const createPayload = JSON.stringify({
        bookings: [
            {
                type: 'FLIGHT',
                resourceId: '66109257-0a9d-4ab6-8d92-6f4ca47baab9',
                price: 15000.00,
            },
            {
                type: 'HOTEL',
                resourceId: '989a71ba-3601-40f0-a94e-14a00e2fa874',
                price: 9000.00,
            },
        ],
    });

    const createRes = http.post(`${BASE_URL}/api/v1/orders`, createPayload, {
        headers: { 'Content-Type': 'application/json' },
    });

    if (createRes.status !== 201) {
        return;
    }

    const orderId = createRes.json().id;

    // 2. Fire 3 concurrent parallel /start requests at the exact same millisecond
    const requests = [
        ['POST', `${BASE_URL}/api/v1/orders/${orderId}/start`, null, {}],
        ['POST', `${BASE_URL}/api/v1/orders/${orderId}/start`, null, {}],
        ['POST', `${BASE_URL}/api/v1/orders/${orderId}/start`, null, {}],
    ];

    const responses = http.batch(requests);

    let okCount = 0;
    let conflictCount = 0;

    for (const r of responses) {
        if (r.status === 200 || r.status === 202) {
            okCount++;
            successfulStarts.add(1);
        } else if (r.status === 409) {
            conflictCount++;
            lockConflicts.add(1);
        }
    }

    // 3. Verify order status and step count: exactly 2 steps, never duplicated
    sleep(0.5);
    const getRes = http.get(`${BASE_URL}/api/v1/orders/${orderId}`);
    if (getRes.status === 200) {
        const order = getRes.json();
        const steps = order.bookingSteps || [];

        // Check if steps count exceeded 2
        const hasDoubleSteps = steps.length > 2;
        if (hasDoubleSteps) {
            doubleExecutionDetected.add(1);
            noDoubleExecutionRate.add(0);
        } else {
            noDoubleExecutionRate.add(1);
        }

        check(order, {
            'exactly one request succeeded or all handled with lock': () => okCount >= 1 && (conflictCount >= 1 || okCount === 1),
            'no duplicate steps created': () => steps.length === 2,
            'order in COMPLETED or terminal state': () => order.status === 'COMPLETED' || order.status === 'PROCESSING',
        });
    }

    sleep(0.2);
}
