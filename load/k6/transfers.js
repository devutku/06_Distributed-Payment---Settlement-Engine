// Mutabık load test: two-way alice <-> bob transfers plus balance reads.
// Run with tools/loadtest.ps1 (k6 in Docker); the app and Keycloak must be running.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://host.docker.internal:8180';
// Local-only demo client from docker/keycloak/mutabik-realm.json
const CLIENT_ID = __ENV.CLIENT_ID || 'mutabik-loadtest';
const CLIENT_SECRET = __ENV.CLIENT_SECRET || 'loadtest-local-only-secret';

const ALICE_ACC = '11111111-1111-1111-1111-111111111111';
const BOB_ACC = '22222222-2222-2222-2222-222222222222';

export const options = {
  scenarios: {
    transfers: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '60s', target: 20 },
        { duration: '15s', target: 0 },
      ],
    },
  },
  thresholds: {
    'http_req_duration{scenario:transfers}': ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

function token(username, password) {
  const res = http.post(`${KEYCLOAK_URL}/realms/mutabik/protocol/openid-connect/token`, {
    grant_type: 'password',
    client_id: CLIENT_ID,
    client_secret: CLIENT_SECRET,
    username,
    password,
  }, { tags: { name: 'keycloak-token' } });
  if (res.status !== 200) {
    throw new Error(`token for ${username} failed: ${res.status} ${res.body}`);
  }
  return res.json('access_token');
}

export function setup() {
  // Tokens live 300 s, longer than the whole run (~105 s)
  return { alice: token('alice', 'alice123'), bob: token('bob', 'bob123') };
}

export default function (tokens) {
  // Alternate direction so balances stay roughly where they started
  const fromAlice = __ITER % 2 === 0;
  const bearer = fromAlice ? tokens.alice : tokens.bob;
  const headers = { Authorization: `Bearer ${bearer}` };

  const transfer = http.post(`${BASE_URL}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: fromAlice ? ALICE_ACC : BOB_ACC,
    targetAccountId: fromAlice ? BOB_ACC : ALICE_ACC,
    amount: '0.01',
    currency: 'TRY',
    description: 'k6 load test',
  }), {
    headers: Object.assign({ 'Content-Type': 'application/json', 'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}` }, headers),
    tags: { name: 'POST /transfers' },
  });
  check(transfer, { 'transfer 200': (r) => r.status === 200 });

  const accounts = http.get(`${BASE_URL}/api/v1/accounts`, { headers, tags: { name: 'GET /accounts' } });
  check(accounts, { 'accounts 200': (r) => r.status === 200 });

  sleep(0.2);
}
