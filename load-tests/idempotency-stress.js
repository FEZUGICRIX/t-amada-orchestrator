import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

export const createdOrders = new Counter('idempotency_created_201');
export const inFlightConflicts = new Counter('idempotency_inflight_conflicts_409');
export const cachedReplays = new Counter('idempotency_cached_replays');
export const idempotencyPassRate = new Rate('idempotency_pass_rate');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    scenarios: {
        idempotent_hammer: {
            executor: 'per-vu-iterations',
            vus: 10,
            iterations: 5,
            maxDuration: '30s',
        },
    },
    thresholds: {
        idempotency_pass_rate: ['rate==1.0'],
    },
};

function generateUuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

export default function () {
    const key = generateUuid();
    const payload = JSON.stringify({
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
        customerContact: {
            phone: '+79991112233',
            email: 'idempotent-stress@example.com',
        },
    });

    const headers = {
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
    };

    // 1. Hammer the server with 4 parallel requests with the EXACT SAME Idempotency-Key
    const batchRequests = [
        ['POST', `${BASE_URL}/api/v1/orders`, payload, { headers }],
        ['POST', `${BASE_URL}/api/v1/orders`, payload, { headers }],
        ['POST', `${BASE_URL}/api/v1/orders`, payload, { headers }],
        ['POST', `${BASE_URL}/api/v1/orders`, payload, { headers }],
    ];

    const responses = http.batch(batchRequests);

    let createdId = null;
    let conflictCount = 0;
    let successCount = 0;
    let validBatchStatuses = true;

    for (const res of responses) {
        if (res.status === 201) {
            successCount++;
            const body = res.json();
            if (body && body.id) {
                if (!createdId) {
                    createdId = body.id;
                    createdOrders.add(1);
                } else {
                    cachedReplays.add(1);
                }
            }
        } else if (res.status === 409) {
            conflictCount++;
            inFlightConflicts.add(1);
        } else {
            validBatchStatuses = false;
        }
    }

    // 2. Wait for completion and verify subsequent sequential replay
    let replayRes;
    let attempts = 0;
    while (attempts < 5) {
        sleep(0.3);
        replayRes = http.post(`${BASE_URL}/api/v1/orders`, payload, { headers });
        if (replayRes.status !== 409) break;
        attempts++;
    }

    const hasOrder = createdId !== null;
    const isReplayOk = check(replayRes, {
        'subsequent replay returns 201 or 200': (r) => r.status === 201 || r.status === 200,
        'subsequent replay returns same order ID': (r) => hasOrder && r.json().id === createdId,
        'subsequent replay has Idempotent-Replayed header': (r) => {
            const h = r.headers['Idempotent-Replayed'] || r.headers['idempotent-replayed'];
            return h === 'true';
        },
    });

    if (isReplayOk && validBatchStatuses && hasOrder) {
        cachedReplays.add(1);
        idempotencyPassRate.add(1);
    } else {
        idempotencyPassRate.add(0);
    }

    sleep(0.2);
}
