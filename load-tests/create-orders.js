import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

// ============================================================================
// Custom Metrics
// ============================================================================
const ordersCreated = new Counter('orders_created');
const sagasStarted = new Counter('sagas_started');
const sagasCompleted = new Counter('sagas_completed');
const sagasCompensated = new Counter('sagas_compensated');
const sagasFailed = new Counter('sagas_failed');
const idempotencyReplays = new Counter('idempotency_replays');
const sagaDuration = new Trend('saga_execution_duration_ms');
const orderCreationDuration = new Trend('order_creation_duration_ms');
const orderGetDuration = new Trend('order_get_duration_ms');
const terminalSagaRate = new Rate('terminal_saga_rate');

// ============================================================================
// Configuration
// ============================================================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FLIGHT_SERVICE_URL = __ENV.SERVICES_FLIGHT_URL || 'http://localhost:3001';
const HOTEL_SERVICE_URL = __ENV.SERVICES_HOTEL_URL || 'http://localhost:3002';
const INSURANCE_SERVICE_URL = __ENV.SERVICES_INSURANCE_URL || 'http://localhost:3003';
const TRANSFER_SERVICE_URL = __ENV.SERVICES_TRANSFER_URL || 'http://localhost:3004';

const TEST_MODE = __ENV.TEST_MODE || 'load'; // 'smoke' | 'load' | 'stress' | '50vu' | '100vu' | '300vu' | 'soak'
const SOAK_DURATION = __ENV.SOAK_DURATION || '10m';

function getStages() {
    if (TEST_MODE === 'smoke') {
        return [
            { duration: '3s', target: 2 },
            { duration: '6s', target: 3 },
            { duration: '2s', target: 0 },
        ];
    }
    if (TEST_MODE === '50vu' || TEST_MODE === '50') {
        return [
            { duration: '5s', target: 25 },
            { duration: '25s', target: 50 },
            { duration: '5s', target: 0 },
        ];
    }
    if (TEST_MODE === '100vu' || TEST_MODE === '100') {
        return [
            { duration: '10s', target: 50 },
            { duration: '30s', target: 100 },
            { duration: '5s', target: 0 },
        ];
    }
    if (TEST_MODE === '300vu' || TEST_MODE === '300') {
        return [
            { duration: '10s', target: 100 },
            { duration: '15s', target: 300 },
            { duration: '30s', target: 300 },
            { duration: '10s', target: 0 },
        ];
    }
    if (TEST_MODE === 'soak') {
        return [
            { duration: '15s', target: 25 },
            { duration: SOAK_DURATION, target: 25 },
            { duration: '15s', target: 0 },
        ];
    }
    if (TEST_MODE === 'stress') {
        return [
            { duration: '10s', target: 10 },
            { duration: '20s', target: 30 },
            { duration: '20s', target: 50 },
            { duration: '10s', target: 0 },
        ];
    }
    // Default 'load' profile
    return [
        { duration: '5s', target: 5 },
        { duration: '15s', target: 15 },
        { duration: '15s', target: 20 },
        { duration: '5s', target: 0 },
    ];
}

export const options = {
    stages: getStages(),

    thresholds: {
        http_req_failed: ['rate<0.02'],
        http_req_duration: ['p(95)<800'],
        checks: ['rate>0.98'],
        terminal_saga_rate: ['rate>0.95'],
    },
};

// ============================================================================
// Helper Utilities
// ============================================================================
function generateUuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

function pickRandom(arr, fallback) {
    if (!arr || arr.length === 0) return fallback;
    const index = Math.floor(Math.random() * arr.length);
    return arr[index];
}

// ============================================================================
// Setup: Discover available booking resources & record baseline metrics
// ============================================================================
export function setup() {
    console.log(`\n======================================================`);
    console.log(`Starting T-Amada Saga Orchestrator Load Test`);
    console.log(`Target Orchestrator: ${BASE_URL}`);
    console.log(`Test Mode: ${TEST_MODE}`);
    console.log(`======================================================\n`);

    const pools = {
        flights: ['66109257-0a9d-4ab6-8d92-6f4ca47baab9'],
        hotels: ['989a71ba-3601-40f0-a94e-14a00e2fa874'],
        insurances: ['04603bb6-ddea-4351-bf37-9386245babef'],
        transfers: ['2e3dc5eb-dd64-4141-8bd9-a5d2e5eac476'],
    };

    // Try fetching full catalogs from running mock services
    try {
        const flightRes = http.get(`${FLIGHT_SERVICE_URL}/api/v1/flight/search`, { timeout: '2s' });
        if (flightRes.status === 200) {
            const list = JSON.parse(flightRes.body);
            if (Array.isArray(list) && list.length > 0) {
                pools.flights = list.map(item => item.id).filter(Boolean);
            }
        }

        const hotelRes = http.get(`${HOTEL_SERVICE_URL}/api/v1/hotel/search`, { timeout: '2s' });
        if (hotelRes.status === 200) {
            const list = JSON.parse(hotelRes.body);
            if (Array.isArray(list) && list.length > 0) {
                pools.hotels = list.map(item => item.id).filter(Boolean);
            }
        }

        const insRes = http.get(`${INSURANCE_SERVICE_URL}/api/v1/insurance/search`, { timeout: '2s' });
        if (insRes.status === 200) {
            const list = JSON.parse(insRes.body);
            if (Array.isArray(list) && list.length > 0) {
                pools.insurances = list.map(item => item.id).filter(Boolean);
            }
        }

        const transRes = http.get(`${TRANSFER_SERVICE_URL}/api/v1/transfer/search`, { timeout: '2s' });
        if (transRes.status === 200) {
            const list = JSON.parse(transRes.body);
            if (Array.isArray(list) && list.length > 0) {
                pools.transfers = list.map(item => item.id).filter(Boolean);
            }
        }
    } catch (e) {
        console.warn('Note: Could not query catalog services directly, using fallback UUIDs.');
    }

    console.log(`Discovered Catalog Pools:`);
    console.log(`  Flights:    ${pools.flights.length} items`);
    console.log(`  Hotels:     ${pools.hotels.length} items`);
    console.log(`  Insurances: ${pools.insurances.length} items`);
    console.log(`  Transfers:  ${pools.transfers.length} items`);

    // Capture initial metric counter values
    let initialOrdersTotal = 0;
    try {
        const metricRes = http.get(`${BASE_URL}/actuator/metrics/orchestrator.saga.orders.total`, { timeout: '3s' });
        if (metricRes.status === 200) {
            const json = JSON.parse(metricRes.body);
            if (json.measurements && json.measurements.length > 0) {
                initialOrdersTotal = json.measurements[0].value;
            }
        }
    } catch (e) {
        // Ignored
    }

    return { pools, initialOrdersTotal };
}

// ============================================================================
// Main Execution Function
// ============================================================================
export default function (data) {
    const pools = data.pools;
    const iterationSeed = Math.random();

    // 70% of traffic executes full End-to-End Async Saga (Create -> 202 Accepted -> Poll COMPLETED)
    // 20% of traffic tests Idempotency replay
    // 10% of traffic tests Sync Saga or Order History Read
    if (iterationSeed < 0.70) {
        scenarioAsyncSaga(pools);
    } else if (iterationSeed < 0.90) {
        scenarioIdempotentOrder(pools);
    } else {
        scenarioSyncOrGetOrder(pools);
    }

    sleep(0.3);
}

// ============================================================================
// Scenario 1: Create & Execute Full Async Saga (RFC 7240 / ?start=true&async=true)
// ============================================================================
function scenarioAsyncSaga(pools) {
    group('Async Saga Orchestration', function () {
        const correlationId = generateUuid();
        const idempotencyKey = generateUuid();

        const flightId = pickRandom(pools.flights, '66109257-0a9d-4ab6-8d92-6f4ca47baab9');
        const hotelId = pickRandom(pools.hotels, '989a71ba-3601-40f0-a94e-14a00e2fa874');
        const insuranceId = pickRandom(pools.insurances, '04603bb6-ddea-4351-bf37-9386245babef');
        const transferId = pickRandom(pools.transfers, '2e3dc5eb-dd64-4141-8bd9-a5d2e5eac476');

        const payload = JSON.stringify({
            bookings: [
                {
                    type: 'FLIGHT',
                    resourceId: flightId,
                    price: 15000.00,
                },
                {
                    type: 'HOTEL',
                    resourceId: hotelId,
                    price: 9000.00,
                    roomsCount: 1,
                    rooms: [
                        {
                            category: 'Стандарт',
                            tariff: 'С завтраком',
                            price: 9000.00,
                            stayConditions: 'Завтрак включен',
                        },
                    ],
                },
                {
                    type: 'INSURANCE',
                    resourceId: insuranceId,
                    price: 1200.00,
                },
                {
                    type: 'TRANSFER',
                    resourceId: transferId,
                    price: 2500.00,
                },
            ],
            passengers: [
                {
                    type: 'ADULT',
                    firstName: 'Иван',
                    lastName: 'Иванов',
                    birthDate: '1990-05-15',
                    citizenship: 'Россия',
                    documentType: 'Паспорт РФ',
                    documentNumber: '4510 123456',
                },
            ],
            customerContact: {
                phone: '+79991234567',
                email: 'ivanov@example.com',
            },
        });

        const startTimestamp = Date.now();

        // 1. Initiate order creation and async orchestration
        const createRes = http.post(
            `${BASE_URL}/api/v1/orders?start=true&async=true`,
            payload,
            {
                headers: {
                    'Content-Type': 'application/json',
                    'Idempotency-Key': idempotencyKey,
                    'X-Correlation-ID': correlationId,
                    'Prefer': 'respond-async',
                },
            }
        );

        orderCreationDuration.add(createRes.timings.duration);

        const accepted = check(createRes, {
            'create async returns 202': (r) => r.status === 202,
            'order id is returned': (r) => {
                const b = r.json();
                return b && b.id !== undefined;
            },
        });

        if (!accepted) {
            terminalSagaRate.add(0);
            return;
        }

        ordersCreated.add(1);
        sagasStarted.add(1);

        const orderId = createRes.json().id;

        // 2. Poll order status until terminal state (COMPLETED or CANCELLED)
        let isTerminal = false;
        let finalStatus = null;
        let attempts = 0;
        const maxAttempts = 25; // allow up to 7.5s during extreme load concurrency spikes

        while (!isTerminal && attempts < maxAttempts) {
            sleep(0.3);
            attempts++;

            const getRes = http.get(`${BASE_URL}/api/v1/orders/${orderId}`, {
                headers: { 'X-Correlation-ID': correlationId },
            });

            orderGetDuration.add(getRes.timings.duration);

            if (getRes.status === 200) {
                const body = getRes.json();
                if (body.status === 'COMPLETED' || body.status === 'CANCELLED' || body.status === 'FAILED') {
                    isTerminal = true;
                    finalStatus = body.status;
                }
            }
        }

        const totalSagaTime = Date.now() - startTimestamp;
        sagaDuration.add(totalSagaTime);

        const terminalSuccess = check(null, {
            'saga reached terminal state': () => isTerminal,
        });

        if (terminalSuccess) {
            if (finalStatus === 'COMPLETED') {
                sagasCompleted.add(1);
            } else if (finalStatus === 'CANCELLED') {
                sagasCompensated.add(1);
            }
            terminalSagaRate.add(1);
        } else {
            sagasFailed.add(1);
            terminalSagaRate.add(0);
        }
    });
}

// ============================================================================
// Scenario 2: Idempotency Key Validation & Replay Caching
// ============================================================================
function scenarioIdempotentOrder(pools) {
    group('Idempotency Cache Replay', function () {
        const idempotencyKey = generateUuid();
        const correlationId = generateUuid();

        const flightId = pickRandom(pools.flights, '66109257-0a9d-4ab6-8d92-6f4ca47baab9');
        const hotelId = pickRandom(pools.hotels, '989a71ba-3601-40f0-a94e-14a00e2fa874');

        const payload = JSON.stringify({
            bookings: [
                {
                    type: 'FLIGHT',
                    resourceId: flightId,
                    price: 12000.00,
                },
                {
                    type: 'HOTEL',
                    resourceId: hotelId,
                    price: 8000.00,
                },
            ],
            customerContact: {
                phone: '+79998887766',
                email: 'idempotent@example.com',
            },
        });

        // 1. First request
        const res1 = http.post(`${BASE_URL}/api/v1/orders`, payload, {
            headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
                'X-Correlation-ID': correlationId,
            },
        });

        const ok1 = check(res1, {
            'first request returns 201': (r) => r.status === 201,
        });

        if (!ok1) return;

        ordersCreated.add(1);
        const orderId1 = res1.json().id;

        // 2. Replay with identical key and body
        const res2 = http.post(`${BASE_URL}/api/v1/orders`, payload, {
            headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
                'X-Correlation-ID': correlationId,
            },
        });

        const replayed = check(res2, {
            'replay returns 201 or 200': (r) => r.status === 201 || r.status === 200,
            'replay returns same order id': (r) => r.json().id === orderId1,
            'has Idempotent-Replayed header': (r) => r.headers['Idempotent-Replayed'] === 'true' || r.headers['idempotent-replayed'] === 'true',
        });

        if (replayed) {
            idempotencyReplays.add(1);
        }
    });
}

// ============================================================================
// Scenario 3: Synchronous Saga Execution & History Query
// ============================================================================
function scenarioSyncOrGetOrder(pools) {
    group('Sync Saga & Audit History', function () {
        const correlationId = generateUuid();
        const flightId = pickRandom(pools.flights, '66109257-0a9d-4ab6-8d92-6f4ca47baab9');
        const insuranceId = pickRandom(pools.insurances, '04603bb6-ddea-4351-bf37-9386245babef');

        const payload = JSON.stringify({
            bookings: [
                {
                    type: 'FLIGHT',
                    resourceId: flightId,
                    price: 14000.00,
                },
                {
                    type: 'INSURANCE',
                    resourceId: insuranceId,
                    price: 1500.00,
                },
            ],
        });

        // 1. Create order
        const createRes = http.post(`${BASE_URL}/api/v1/orders`, payload, {
            headers: {
                'Content-Type': 'application/json',
                'X-Correlation-ID': correlationId,
            },
        });

        if (createRes.status !== 201) return;
        ordersCreated.add(1);
        const orderId = createRes.json().id;

        // 2. Start saga synchronously
        const startTimestamp = Date.now();
        const startRes = http.post(`${BASE_URL}/api/v1/orders/${orderId}/start`, null, {
            headers: { 'X-Correlation-ID': correlationId },
        });

        sagaDuration.add(Date.now() - startTimestamp);

        const syncSuccess = check(startRes, {
            'sync start returns 200': (r) => r.status === 200,
            'order status is COMPLETED or CANCELLED': (r) => {
                const b = r.json();
                return b.status === 'COMPLETED' || b.status === 'CANCELLED';
            },
        });

        if (syncSuccess) {
            const body = startRes.json();
            if (body.status === 'COMPLETED') {
                sagasCompleted.add(1);
            } else if (body.status === 'CANCELLED') {
                sagasCompensated.add(1);
            }
            terminalSagaRate.add(1);
        } else {
            sagasFailed.add(1);
            terminalSagaRate.add(0);
        }

        // 3. Inspect history audit log
        const histRes = http.get(`${BASE_URL}/api/v1/orders/${orderId}/history`, {
            headers: { 'X-Correlation-ID': correlationId },
        });

        check(histRes, {
            'history returns 200': (r) => r.status === 200,
            'history has entries': (r) => Array.isArray(r.json()) && r.json().length > 0,
        });
    });
}

// ============================================================================
// Teardown: Query Spring Boot Actuator & Prometheus Metrics and Print Summary
// ============================================================================
export function teardown(data) {
    console.log(`\n======================================================`);
    console.log(`T-Amada Saga Orchestrator Load Test Completed`);
    console.log(`Fetching Actuator & Prometheus Metrics from ${BASE_URL}...`);
    console.log(`======================================================\n`);

    try {
        const orderMetricsRes = http.get(`${BASE_URL}/actuator/metrics/orchestrator.saga.orders.total`);
        const stepMetricsRes = http.get(`${BASE_URL}/actuator/metrics/orchestrator.saga.steps.total`);
        const durationMetricsRes = http.get(`${BASE_URL}/actuator/metrics/orchestrator.saga.order.duration`);
        const retriesMetricsRes = http.get(`${BASE_URL}/actuator/metrics/orchestrator.booking.client.retries.total`);
        const compensationMetricsRes = http.get(`${BASE_URL}/actuator/metrics/orchestrator.saga.compensations.total`);
        const outboxMetricsRes = http.get(`${BASE_URL}/actuator/metrics/orchestrator.outbox.published.total`);
        const outboxBacklogRes = http.get(`${BASE_URL}/actuator/metrics/orchestrator.outbox.pending.backlog`);
        const hikariActiveRes = http.get(`${BASE_URL}/actuator/metrics/hikaricp.connections.active`);
        const hikariPendingRes = http.get(`${BASE_URL}/actuator/metrics/hikaricp.connections.pending`);

        console.log(`[SPRING BOOT ACTUATOR SAGA METRICS REPORT]`);

        if (orderMetricsRes.status === 200) {
            const body = JSON.parse(orderMetricsRes.body);
            const count = body.measurements && body.measurements[0] ? body.measurements[0].value : '0';
            console.log(`  - Total Terminal Sagas:       ${count} (Δ: +${count - (data.initialOrdersTotal || 0)})`);
        }

        if (stepMetricsRes.status === 200) {
            const body = JSON.parse(stepMetricsRes.body);
            const count = body.measurements && body.measurements[0] ? body.measurements[0].value : '0';
            console.log(`  - Total Booking Steps Done:   ${count}`);
        }

        if (durationMetricsRes.status === 200) {
            const body = JSON.parse(durationMetricsRes.body);
            const count = body.measurements && body.measurements[0] ? body.measurements[0].value : '0';
            const totalTime = body.measurements && body.measurements[1] ? body.measurements[1].value : '0';
            const maxTime = body.measurements && body.measurements[2] ? body.measurements[2].value : '0';
            const avg = count > 0 ? (totalTime / count).toFixed(3) : 0;
            console.log(`  - Saga Duration (SLA):        count=${count}, avg=${avg}s, max=${Number(maxTime).toFixed(3)}s`);
        }

        if (retriesMetricsRes.status === 200) {
            const body = JSON.parse(retriesMetricsRes.body);
            const count = body.measurements && body.measurements[0] ? body.measurements[0].value : '0';
            console.log(`  - Client Retries Total:       ${count}`);
        }

        if (compensationMetricsRes.status === 200) {
            const body = JSON.parse(compensationMetricsRes.body);
            const count = body.measurements && body.measurements[0] ? body.measurements[0].value : '0';
            console.log(`  - Compensations Total:        ${count}`);
        }

        if (outboxMetricsRes.status === 200) {
            const body = JSON.parse(outboxMetricsRes.body);
            const count = body.measurements && body.measurements[0] ? body.measurements[0].value : '0';
            console.log(`  - Kafka Outbox Published:     ${count}`);
        }

        if (outboxBacklogRes.status === 200) {
            const body = JSON.parse(outboxBacklogRes.body);
            const count = body.measurements && body.measurements[0] ? body.measurements[0].value : '0';
            console.log(`  - Outbox Pending Backlog:     ${count} (target: 0)`);
        }

        if (hikariActiveRes.status === 200) {
            const body = JSON.parse(hikariActiveRes.body);
            const active = body.measurements && body.measurements[0] ? body.measurements[0].value : '0';
            let pending = '0';
            if (hikariPendingRes.status === 200) {
                const pBody = JSON.parse(hikariPendingRes.body);
                pending = pBody.measurements && pBody.measurements[0] ? pBody.measurements[0].value : '0';
            }
            console.log(`  - HikariCP Pool Connections:  active=${active}, pending=${pending}`);
        }

        const healthRes = http.get(`${BASE_URL}/actuator/health`);
        if (healthRes.status === 200) {
            const body = JSON.parse(healthRes.body);
            console.log(`  - Overall System Health:      ${body.status}`);
        }
    } catch (e) {
        console.warn(`Could not retrieve actuator metrics: ${e.message}`);
    }

    console.log(`\n======================================================\n`);
}
