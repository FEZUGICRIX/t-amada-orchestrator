import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '10s', target: 5 },
        { duration: '20s', target: 15 },
        { duration: '20s', target: 30 },
        { duration: '10s', target: 0 },
    ],

    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
        checks: ['rate>0.99'],
    },
};

export default function () {
    const payload = JSON.stringify({
        bookings: [
            {
                type: 'FLIGHT',
                resourceId: '10000000-0000-0000-0000-000000000001',
            },
            {
                type: 'HOTEL',
                resourceId: '20000000-0000-0000-0000-000000000002',
            },
            {
                type: 'INSURANCE',
                resourceId: '30000000-0000-0000-0000-000000000003',
            },
            {
                type: 'TRANSFER',
                resourceId: '40000000-0000-0000-0000-000000000004',
            },
        ],
    });

    const response = http.post(
        'http://localhost:8080/api/v1/orders',
        payload,
        {
            headers: {
                'Content-Type': 'application/json',
            },
        }
    );

    check(response, {
        'status is 201': (r) => r.status === 201,
    });

    sleep(0.2);
}
