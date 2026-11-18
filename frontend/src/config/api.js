// THE single API config file. Base URL and every endpoint path live here.
// No component may hard-code a URL.

const runtime = (typeof window !== 'undefined' && window.__APP_CONFIG__) || {};

export const API_BASE_URL =
  runtime.API_BASE_URL || import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const USE_MOCKS =
  String(runtime.USE_MOCKS ?? import.meta.env.VITE_USE_MOCKS ?? 'true') === 'true';

export const REQUEST_TIMEOUT_MS = 15000;
export const POLL_INTERVAL_MS = 5000;
export const LIVE_POLL_INTERVAL_MS = 1500; // faster refresh while a 3PC settlement is in flight

export const ENDPOINTS = {
  auth: {
    login:   '/api/auth/login',            // POST {username, password} -> {token, user:{id,name,role}}
    logout:  '/api/auth/logout',           // POST
    me:      '/api/auth/me',               // GET
  },
  policy: {
    byNumber: (policyNo) => `/api/policies/${policyNo}`,            // GET
    coverage: (policyNo) => `/api/policies/${policyNo}/coverage`,   // GET
    mine:     '/api/policies/me',                                   // GET (policyholder)
  },
  claims: {
    create:      '/api/claims',                                     // POST
    mine:        '/api/claims/me',                                  // GET
    byId:        (id) => `/api/claims/${id}`,                       // GET
    status:      (id) => `/api/claims/${id}/status`,                // GET
    uploadUrl:   (id) => `/api/claims/${id}/documents/upload-url`,  // POST {fileName, contentType, docType} -> {uploadUrl, documentId}
    documents:   (id) => `/api/claims/${id}/documents`,             // GET
    queue:       '/api/claims/queue',                               // GET ?status=&page=&size= (adjuster)
    all:         '/api/claims',                                     // GET ?status=&q=&page=&size= (adjuster)
  },
  eligibility: {
    get:      (id) => `/api/eligibility/${id}`,                     // GET
    override: (id) => `/api/eligibility/${id}/override`,            // PUT {eligible, reason, comment}
  },
  assessment: {
    get:    (id) => `/api/assessments/${id}`,                       // GET
    adjust: (id) => `/api/assessments/${id}/adjust`,                // PUT {amount, comment}
  },
  approval: {
    decide:     (id) => `/api/approvals/${id}`,                     // POST {decision:'APPROVED'|'REJECTED', comment}
    settlement: (id) => `/api/approvals/${id}/settlement`,          // GET 3PC transaction state + participant votes
    retry:      (id) => `/api/approvals/${id}/settlement/retry`,    // POST
  },
  audit: {
    trail: (id) => `/api/audit/claims/${id}`,                       // GET timeline of events
    search: '/api/audit/events',                                    // GET ?type=&from=&to=
  },
  analytics: {
    summary: '/api/analytics/summary',                              // GET latest window
    series:  '/api/analytics/series',                               // GET ?metric=&window=1m&from=
    fraudFlags: '/api/analytics/fraud-flags',                       // GET policies with >2 claims in 10 min
  },
  ops: {
    chaos: '/api/ops/chaos',                                        // POST {target:'PAYMENT'|'COORDINATOR', mode:'FAIL'|'CRASH_AFTER_PRECOMMIT'|'OFF'}
                                                                    // GET  -> current state of each toggle
  },
};

// ---------------------------------------------------------------------------
// Partial mocking: which ENDPOINTS groups the mock backend answers.
//   VITE_MOCK_GROUPS (or window.__APP_CONFIG__.MOCK_GROUPS) = 'all' (default)
//   or a comma list of group names, e.g. 'policy,claims,eligibility,assessment,approval,audit,analytics,ops'
//   to send only `auth` to the real API. Has no effect when USE_MOCKS is false.
// ---------------------------------------------------------------------------
const rawMockGroups = String(runtime.MOCK_GROUPS ?? import.meta.env.VITE_MOCK_GROUPS ?? 'all').trim();

export const MOCK_GROUPS = rawMockGroups === '' || rawMockGroups === 'all'
  ? Object.keys(ENDPOINTS)
  : rawMockGroups.split(',').map((g) => g.trim()).filter((g) => g in ENDPOINTS);

// Each endpoint path (or path template) as a regex, tagged with its group.
const ENDPOINT_PATTERNS = Object.entries(ENDPOINTS).flatMap(([group, entries]) =>
  Object.values(entries).map((ep) => {
    const template = typeof ep === 'function' ? ep('\u0000') : ep;
    const source = template.split('\u0000').map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('[^/]+');
    return { group, pattern: new RegExp(`^${source}$`) };
  }));

/** The ENDPOINTS group a request path belongs to (e.g. '/api/claims/C-1/status' -> 'claims'), or null. */
export function endpointGroup(path) {
  const clean = path.split('?')[0];
  return ENDPOINT_PATTERNS.find((p) => p.pattern.test(clean))?.group ?? null;
}

/** Whether this request path is answered by the mock backend. */
export function isMocked(path) {
  if (!USE_MOCKS) return false;
  const group = endpointGroup(path);
  return group === null ? MOCK_GROUPS.length === Object.keys(ENDPOINTS).length : MOCK_GROUPS.includes(group);
}
