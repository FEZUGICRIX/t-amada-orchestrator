import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

export const compensationsTriggered = new Counter('fault_compensations_triggered');
export const faultRecoverySuccess = new Counter('fault_recovery_success');
export const faultToleranceRate = new Rate('fault_tolerance_rate');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const INVALID_RESOURCE_ID = '00000000-0000-0000-0000-000000000000';

export const options = {
    scenarios: {
        fault_injection: {
            executor: 'per-vu-iterations',
            vus: 5,
            iterations: 4,
            maxDuration: '30s',
        },
    },
    thresholds: {
        fault_tolerance_rate: ['rate==1.0'],
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
    const correlationId = generateUuid();

    // 1. Create order where FLIGHT is VALID, but HOTEL has an INVALID resourceId (forcing failure)
    const payload = JSON.stringify({
        bookings: [
            {
                type: 'FLIGHT',
                resourceId: '66109257-0a9d-4ab6-8d92-6f4ca47baab9',
                price: 15000.00,
            },
            {
                type: 'HOTEL',
                resourceId: INVALID_RESOURCE_ID,
                price: 9000.00,
            },
        ],
        customerContact: {
            phone: '+79997776655',
            email: 'fault-test@example.com',
        },
    });

    const createRes = http.post(`${BASE_URL}/api/v1/orders`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'X-Correlation-ID': correlationId,
        },
    });

    if (createRes.status !== 201) return;
    const orderId = createRes.json().id;

    // 2. Start saga: FLIGHT succeeds, HOTEL fails, FLIGHT is compensated, order ends in CANCELLED or FAILED
    const startRes = http.post(`${BASE_URL}/api/v1/orders/${orderId}/start`, null, {
        headers: { 'X-Correlation-ID': correlationId },
    });

    const terminalStatus = startRes.json() ? startRes.json().status : null;
    const isCompensatedOrFailed = terminalStatus === 'CANCELLED' || terminalStatus === 'FAILED';

    if (isCompensatedOrFailed) {
        compensationsTriggered.add(1);
    }

    const startCheck = check(startRes, {
        'saga fails gracefully and handles fault': () => isCompensatedOrFailed,
        'has failure reason recorded': (r) => {
            const b = r.json();
            return b && (b.failureReason !== null || b.userMessage !== null);
        },
    });

    // 3. Test Partial Compensation & Hotel Replacement if order is FAILED
    if (terminalStatus === 'FAILED') {
        const replacePayload = JSON.stringify({
            resourceId: '989a71ba-3601-40f0-a94e-14a00e2fa874',
            price: 9500.00,
        });

        const replaceRes = http.post(`${BASE_URL}/api/v1/orders/${orderId}/hotel/replace`, replacePayload, {
            headers: {
                'Content-Type': 'application/json',
                'X-Correlation-ID': correlationId,
            },
        });

        if (replaceRes.status === 200) {
            // Resume saga
            const resumeRes = http.post(`${BASE_URL}/api/v1/orders/${orderId}/start`, null, {
                headers: { 'X-Correlation-ID': correlationId },
            });

            if (resumeRes.json() && resumeRes.json().status === 'COMPLETED') {
                faultRecoverySuccess.add(1);
            }
        }
    }

    if (startCheck) {
        faultToleranceRate.add(1);
    } else {
        faultToleranceRate.add(0);
    }

    sleep(0.3);
}
