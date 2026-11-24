import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

export const dbWriteDuration = new Trend('db_order_creation_duration_ms');
export const dbReadDuration = new Trend('db_history_read_duration_ms');
export const dbStressSuccessRate = new Rate('db_stress_success_rate');
export const dbOrdersCreated = new Counter('db_orders_created');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    scenarios: {
        db_stress: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '5s', target: 20 },
                { duration: '20s', target: 50 },
                { duration: '5s', target: 0 },
            ],
            gracefulRampDown: '5s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.02'],
        db_stress_success_rate: ['rate>0.98'],
        db_order_creation_duration_ms: ['p(95)<300'],
        db_history_read_duration_ms: ['p(95)<200'],
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
    const idempotencyKey = `db-stress-${correlationId}`;

    // 1. Create a rich order containing multiple booking items, valid passengers, and contact info
    const orderPayload = JSON.stringify({
        bookings: [
            {
                type: 'FLIGHT',
                resourceId: '66109257-0a9d-4ab6-8d92-6f4ca47baab9',
                price: 18500.00,
            },
            {
                type: 'HOTEL',
                resourceId: '989a71ba-3601-40f0-a94e-14a00e2fa874',
                price: 12000.00,
            },
            {
                type: 'INSURANCE',
                resourceId: '04603bb6-ddea-4351-bf37-9386245babef',
                price: 2500.00,
            },
            {
                type: 'TRANSFER',
                resourceId: '2e3dc5eb-dd64-4141-8bd9-a5d2e5eac476',
                price: 3500.00,
            },
        ],
        passengers: [
            {
                type: 'ADULT',
                firstName: 'Иван',
                lastName: 'Петров',
                birthDate: '1985-06-15',
                citizenship: 'Россия',
                documentType: 'Паспорт РФ',
                documentNumber: '4510 123456',
            },
            {
                type: 'ADULT',
                firstName: 'Елена',
                lastName: 'Петрова',
                birthDate: '1988-09-20',
                citizenship: 'Россия',
                documentType: 'Паспорт РФ',
                documentNumber: '4510 654321',
            },
        ],
        customerContact: {
            phone: '+79991234567',
            email: 'db-stress@example.com',
            preferredLanguage: 'RU',
        },
    });

    const createStart = Date.now();
    const createRes = http.post(`${BASE_URL}/api/v1/orders`, orderPayload, {
        headers: {
            'Content-Type': 'application/json',
            'X-Correlation-ID': correlationId,
            'Idempotency-Key': idempotencyKey,
        },
    });
    dbWriteDuration.add(Date.now() - createStart);

    const isCreated = check(createRes, {
        'DB order created status 201': (r) => r.status === 201,
        'DB order returned valid ID': (r) => r.json() && r.json().id !== undefined,
        'DB order has 4 booking steps': (r) => r.json() && r.json().bookingSteps && r.json().bookingSteps.length === 4,
    });

    if (!isCreated) {
        dbStressSuccessRate.add(0);
        return;
    }

    dbOrdersCreated.add(1);
    const orderId = createRes.json().id;

    // 2. Perform concurrent reads on the newly persisted entities
    const getRes = http.get(`${BASE_URL}/api/v1/orders/${orderId}`, {
        headers: { 'X-Correlation-ID': correlationId },
    });

    check(getRes, {
        'GET order status 200': (r) => r.status === 200,
        'Order contains correct passengers': (r) => r.json() && r.json().passengers && r.json().passengers.length === 2,
    });

    // 3. Query audit log history (complex JPA join query on order_history)
    const readStart = Date.now();
    const histRes = http.get(`${BASE_URL}/api/v1/orders/${orderId}/history`, {
        headers: { 'X-Correlation-ID': correlationId },
    });
    dbReadDuration.add(Date.now() - readStart);

    const isHistOk = check(histRes, {
        'GET order history status 200': (r) => r.status === 200,
        'Audit history has entries': (r) => Array.isArray(r.json()) && r.json().length > 0,
    });

    dbStressSuccessRate.add(isHistOk ? 1 : 0);
    sleep(0.3);
}

export function teardown() {
    console.log(`\n======================================================`);
    console.log(`DB Stress Test Completed - Checking PostgreSQL & Connection Pool`);
    console.log(`======================================================\n`);

    try {
        const hikariActive = http.get(`${BASE_URL}/actuator/metrics/hikaricp.connections.active`);
        const hikariIdle = http.get(`${BASE_URL}/actuator/metrics/hikaricp.connections.idle`);
        const hikariAcquire = http.get(`${BASE_URL}/actuator/metrics/hikaricp.connections.acquire`);
        const hikariPending = http.get(`${BASE_URL}/actuator/metrics/hikaricp.connections.pending`);

        console.log(`[HIKARICP POSTGRESQL CONNECTION POOL STATUS]`);
        if (hikariActive.status === 200) {
            console.log(`  - Active Connections:   ${hikariActive.json().measurements[0].value}`);
        }
        if (hikariIdle.status === 200) {
            console.log(`  - Idle Connections:     ${hikariIdle.json().measurements[0].value}`);
        }
        if (hikariPending.status === 200) {
            console.log(`  - Pending Threads:      ${hikariPending.json().measurements[0].value}`);
        }
        if (hikariAcquire.status === 200) {
            const m = hikariAcquire.json().measurements;
            console.log(`  - Acquire Count:        ${m[0].value}, Total Time: ${m[1].value}s, Max: ${m[2].value}s`);
        }
    } catch (e) {
        console.warn(`Could not fetch connection pool metrics: ${e.message}`);
    }
    console.log(`\n======================================================\n`);
}
