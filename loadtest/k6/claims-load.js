// k6 load test for the Claims Platform: the same REST calls the website makes, at a chosen intensity.
//
//   k6 run -e BASE_URL=http://<ingress> -e PROFILE=load loadtest/k6/claims-load.js
//
// Usually started through loadtest/run.sh, which also records the HPAs and writes the Markdown report.
//
// Three kinds of virtual user run side by side:
//   policyholder  browses their policy and claims and sometimes files a claim (Policy, Claim Intake, gRPC between them)
//   adjuster      works the review queue and searches claims (Claim Intake)
//   signIn        signs in and out at a steady rate (Login: bcrypt makes this CPU-heavy, so it exercises the HPA)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:5173').replace(/\/$/, '');
const PROFILE = __ENV.PROFILE || 'smoke';
const WRITES = (__ENV.WRITES || 'true') === 'true';   // false: read-only (no claims are created)
const PASSWORD = __ENV.PASSWORD || 'password';
const PATIENT = __ENV.PATIENT_USER || 'patient1';
const ADJUSTER = __ENV.ADJUSTER_USER || 'adjuster1';

// stages for the two browsing scenarios (VUs) and the sign-in rate (iterations per second)
const PROFILES = {
  smoke:  { users: [{ duration: '20s', target: 2 }, { duration: '40s', target: 2 }],
            signIns: [{ duration: '1m', target: 1 }] },
  load:   { users: [{ duration: '2m', target: 40 }, { duration: '6m', target: 40 }, { duration: '2m', target: 0 }],
            signIns: [{ duration: '2m', target: 5 }, { duration: '6m', target: 5 }, { duration: '2m', target: 0 }] },
  stress: { users: [{ duration: '2m', target: 50 }, { duration: '3m', target: 100 }, { duration: '3m', target: 150 },
                    { duration: '2m', target: 0 }],
            signIns: [{ duration: '2m', target: 5 }, { duration: '6m', target: 15 }, { duration: '2m', target: 0 }] },
};
const P = PROFILES[PROFILE];
if (!P) throw new Error(`Unknown PROFILE "${PROFILE}". Use smoke, load or stress.`);

const scaled = (stages, share) => stages.map((s) => ({ duration: s.duration, target: Math.max(1, Math.round(s.target * share)) }))
  .map((s, i, all) => (i === all.length - 1 && stages[i].target === 0 ? { ...s, target: 0 } : s));

export const options = {
  scenarios: {
    policyholder: { executor: 'ramping-vus', exec: 'policyholder', startVUs: 0, stages: scaled(P.users, 0.7), gracefulRampDown: '10s' },
    adjuster:     { executor: 'ramping-vus', exec: 'adjuster', startVUs: 0, stages: scaled(P.users, 0.3), gracefulRampDown: '10s' },
    signIn:       { executor: 'ramping-arrival-rate', exec: 'signIn', startRate: 0, timeUnit: '1s',
                    preAllocatedVUs: 5, maxVUs: 50, stages: P.signIns },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],                 // under 1 % of requests fail
    http_req_duration: ['p(95)<1000'],              // 95 % answer within 1 s
    'http_req_duration{name:POST /api/auth/login}': ['p(95)<1500'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const claimsFiled = new Counter('claims_filed');
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function login(username) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ username, password: PASSWORD }),
    { headers: JSON_HEADERS, tags: { name: 'POST /api/auth/login' } });
  check(res, { 'login 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json('token') : null;
}

const auth = (token, name) => ({ headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` }, tags: { name } });

// Sign in once per user type and share the tokens with every VU (the login scenario measures sign-in itself).
export function setup() {
  const patient = login(PATIENT);
  const adjuster = login(ADJUSTER);
  if (!patient || !adjuster) throw new Error(`Could not sign in at ${BASE_URL}. Is the backend running?`);
  const policy = http.get(`${BASE_URL}/api/policies/me`, auth(patient, 'GET /api/policies/me')).json();
  return { patient, adjuster, policyNumber: policy.policyNumber, memberId: policy.members[0].id };
}

export function policyholder(data) {
  const t = data.patient;
  let r = http.get(`${BASE_URL}/api/policies/me`, auth(t, 'GET /api/policies/me'));
  check(r, { 'policy 200': (x) => x.status === 200 });
  r = http.get(`${BASE_URL}/api/policies/${data.policyNumber}/coverage`, auth(t, 'GET /api/policies/{no}/coverage'));
  check(r, { 'coverage 200': (x) => x.status === 200 });

  r = http.get(`${BASE_URL}/api/claims/me`, auth(t, 'GET /api/claims/me'));
  check(r, { 'my claims 200': (x) => x.status === 200 });
  const claims = r.status === 200 ? listOf(r.json()) : [];
  if (claims.length > 0) {
    const id = claims[Math.floor(Math.random() * claims.length)].id;
    check(http.get(`${BASE_URL}/api/claims/${id}`, auth(t, 'GET /api/claims/{id}')), { 'claim 200': (x) => x.status === 200 });
    check(http.get(`${BASE_URL}/api/claims/${id}/status`, auth(t, 'GET /api/claims/{id}/status')), { 'status 200': (x) => x.status === 200 });
  }

  // one browse in ten files a new claim (Claim Intake → Policy over gRPC)
  if (WRITES && Math.random() < 0.1) {
    const today = new Date().toISOString().slice(0, 10);
    const body = {
      policyNumber: data.policyNumber, patient: { memberId: data.memberId }, hospital: 'Load Test Hospital',
      admissionDate: today, dischargeDate: today, diagnosis: 'k6 load test', treatmentType: 'Hospitalisation',
      claimedAmount: 1000 + Math.floor(Math.random() * 9000),
    };
    r = http.post(`${BASE_URL}/api/claims`, JSON.stringify(body), auth(t, 'POST /api/claims'));
    if (check(r, { 'claim filed 201': (x) => x.status === 201 })) claimsFiled.add(1);
  }
  sleep(1 + Math.random() * 2);   // think time, like a person reading the page
}

export function adjuster(data) {
  const t = data.adjuster;
  check(http.get(`${BASE_URL}/api/claims/queue?page=0&size=20`, auth(t, 'GET /api/claims/queue')),
    { 'queue 200': (x) => x.status === 200 });
  check(http.get(`${BASE_URL}/api/claims?q=Load&page=0&size=20`, auth(t, 'GET /api/claims?q=')),
    { 'search 200': (x) => x.status === 200 });
  sleep(1 + Math.random() * 2);
}

export function signIn() {
  const token = login(Math.random() < 0.5 ? PATIENT : ADJUSTER);
  if (token) {
    check(http.get(`${BASE_URL}/api/auth/me`, auth(token, 'GET /api/auth/me')), { 'me 200': (x) => x.status === 200 });
    http.post(`${BASE_URL}/api/auth/logout`, null, auth(token, 'POST /api/auth/logout'));
  }
}

// a page ({content: [...]}) or a plain list
function listOf(body) {
  return Array.isArray(body) ? body : (body && body.content) || [];
}

// summary.json for the report; a short line on the console
export function handleSummary(data) {
  const out = __ENV.RESULTS_DIR ? `${__ENV.RESULTS_DIR}/summary.json` : 'summary.json';
  const failed = Object.entries(data.metrics).filter(([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok));
  const reqs = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0;
  const p95 = data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(95)'].toFixed(0) : '?';
  return {
    [out]: JSON.stringify({ ...data, meta: { baseUrl: BASE_URL, profile: PROFILE, writes: WRITES } }, null, 1),
    stdout: `\n${reqs} requests, p95 ${p95} ms, thresholds ${failed.length ? 'FAILED: ' + failed.map(([n]) => n).join(', ') : 'all passed'}\n`,
  };
}
