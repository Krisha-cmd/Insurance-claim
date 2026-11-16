// Mock backend. Handlers are registered against the same ENDPOINTS entries the
// app uses, so switching USE_MOCKS off changes nothing else.

import { ENDPOINTS } from '../../config/api.js';
import { DOC_TYPES, REVIEWABLE_STATUSES } from '../../lib/status.js';
import {
  state, SERVICES, audit, setStatus, createClaim, addDocument, markUploaded, verifyDocument,
  docsComplete, runEligibility, runAssessment, overrideEligibility, adjustAssessment, decide,
  settlementSteps, runStepsLive, currentScenario,
} from './engine.js';
import { seed } from './seed.js';
import { analyticsSummary, analyticsSeries, fraudFlags } from './analytics.js';

seed();

/* ---------------------------------------------------------------- routing */

const routes = [];

/** Register a handler. `endpoint` is an ENDPOINTS string or path function. */
function on(method, endpoint, handler) {
  const template = typeof endpoint === 'function' ? endpoint(':param') : endpoint;
  const names = [];
  const pattern = new RegExp(`^${template.replace(/:param/g, () => { names.push('param'); return '([^/]+)'; })}$`);
  routes.push({ method, pattern, dynamic: names.length > 0, handler });
}

function fail(status, message) {
  const err = new Error(message);
  err.status = status;
  throw err;
}

const delay = () => new Promise((r) => setTimeout(r, 300 + Math.random() * 500));
const clone = (x) => (x === undefined ? null : structuredClone(x));

function userFromToken(token) {
  const id = token?.startsWith('mock-token-') ? token.slice('mock-token-'.length) : null;
  return id ? state.users[id] : null;
}

export async function handleMock({ method, path, body, params = {}, token }) {
  await delay();
  // Static routes win over dynamic ones (e.g. /api/claims/queue before /api/claims/:id).
  const ordered = [...routes.filter((r) => !r.dynamic), ...routes.filter((r) => r.dynamic)];
  for (const r of ordered) {
    if (r.method !== method) continue;
    const m = path.match(r.pattern);
    if (!m) continue;
    const user = userFromToken(token);
    const result = await r.handler({ body, params, user, id: m[1] && decodeURIComponent(m[1]) });
    return clone(result);
  }
  fail(404, `No mock for ${method} ${path}`);
}

/* ---------------------------------------------------------------- guards */

const publicUser = (u) => ({ id: u.id, name: u.name, role: u.role });

function requireUser(user) { if (!user) fail(401, 'Your session has expired. Please sign in again.'); return user; }
function requireAdjuster(user) {
  requireUser(user);
  if (user.role !== 'ADJUSTER') fail(403, 'Only adjusters can do that.');
  return user;
}
function getClaim(id, user) {
  requireUser(user);
  const c = state.claims[id];
  if (!c) fail(404, `Claim ${id} not found.`);
  if (user.role === 'POLICYHOLDER' && c.policyholderId !== user.id) fail(403, 'This claim belongs to another policyholder.');
  return c;
}
function requireComment(comment) {
  if (!comment || comment.trim().length < 10) fail(400, 'A justification comment of at least 10 characters is required.');
}
function paginate(items, { page = 0, size = 50 }) {
  const p = Number(page); const s = Number(size);
  return { items: items.slice(p * s, p * s + s), page: p, size: s, total: items.length };
}

/* ------------------------------------------------------------------ auth */

on('POST', ENDPOINTS.auth.login, ({ body }) => {
  const u = Object.values(state.users).find((x) => x.username === body?.username?.trim());
  if (!u || u.password !== body?.password) fail(401, 'Incorrect username or password.');
  return { token: `mock-token-${u.id}`, user: publicUser(u) };
});
on('POST', ENDPOINTS.auth.logout, () => ({ ok: true }));
on('GET', ENDPOINTS.auth.me, ({ user }) => publicUser(requireUser(user)));

/* ---------------------------------------------------------------- policy */

function policyView(p) {
  const { coverage, ...rest } = p;
  return { ...rest, deductible: coverage.deductible, coPayPercent: coverage.coPayPercent };
}

on('GET', ENDPOINTS.policy.mine, ({ user }) => {
  requireUser(user);
  if (!user.policyNumber) fail(404, 'No policy linked to this account.');
  return policyView(state.policies[user.policyNumber]);
});
on('GET', ENDPOINTS.policy.byNumber, ({ id, user }) => {
  requireUser(user);
  const p = state.policies[id];
  if (!p) fail(404, `Policy ${id} not found.`);
  if (user.role === 'POLICYHOLDER' && p.holderId !== user.id) fail(403, 'This policy belongs to someone else.');
  return policyView(p);
});
on('GET', ENDPOINTS.policy.coverage, ({ id, user }) => {
  requireUser(user);
  const p = state.policies[id];
  if (!p) fail(404, `Policy ${id} not found.`);
  if (user.role === 'POLICYHOLDER' && p.holderId !== user.id) fail(403, 'This policy belongs to someone else.');
  return { policyNumber: p.policyNumber, validFrom: p.validFrom, validTo: p.validTo, sumInsured: p.sumInsured, ...p.coverage };
});

/* ---------------------------------------------------------------- claims */

const newestFirst = (a, b) => b.createdAt.localeCompare(a.createdAt);

on('GET', ENDPOINTS.claims.mine, ({ user }) => {
  requireUser(user);
  return Object.values(state.claims).filter((c) => c.policyholderId === user.id).sort(newestFirst);
});

on('GET', ENDPOINTS.claims.queue, ({ user, params }) => {
  requireAdjuster(user);
  const statuses = params.status ? [params.status] : [...REVIEWABLE_STATUSES];
  const items = Object.values(state.claims)
    .filter((c) => statuses.includes(c.status))
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  return paginate(items, params);
});

on('GET', ENDPOINTS.claims.all, ({ user, params }) => {
  requireAdjuster(user);
  const q = (params.q || '').trim().toLowerCase();
  const from = params.from ? new Date(params.from).getTime() : null;
  const to = params.to ? new Date(params.to).getTime() + 86400000 : null;
  const items = Object.values(state.claims).filter((c) => {
    if (params.status && c.status !== params.status) return false;
    if (q && !c.id.toLowerCase().includes(q) && !c.policyNumber.toLowerCase().includes(q)) return false;
    const t = new Date(c.createdAt).getTime();
    if (from && t < from) return false;
    if (to && t >= to) return false;
    return true;
  }).sort(newestFirst);
  return paginate(items, params);
});

on('POST', ENDPOINTS.claims.create, ({ user, body }) => {
  requireUser(user);
  if (user.role !== 'POLICYHOLDER') fail(403, 'Only policyholders can file claims.');
  const required = ['hospital', 'admissionDate', 'dischargeDate', 'diagnosis', 'treatmentType', 'claimedAmount'];
  const missing = required.filter((k) => !body?.[k]);
  if (missing.length) fail(400, `Missing fields: ${missing.join(', ')}`);
  return createClaim({ ...body, policyNumber: user.policyNumber }, user, Date.now());
});

on('GET', ENDPOINTS.claims.byId, ({ id, user }) => getClaim(id, user));
on('GET', ENDPOINTS.claims.status, ({ id, user }) => {
  const c = getClaim(id, user);
  return { id: c.id, status: c.status, updatedAt: c.updatedAt };
});
on('GET', ENDPOINTS.claims.documents, ({ id, user }) => getClaim(id, user).documents);

on('POST', ENDPOINTS.claims.uploadUrl, ({ id, user, body }) => {
  const c = getClaim(id, user);
  if (!DOC_TYPES.some((d) => d.value === body?.docType)) fail(400, 'Unknown document type.');
  const doc = addDocument(c, { docType: body.docType, fileName: body.fileName, contentType: body.contentType, size: body.size }, user, Date.now());
  return { uploadUrl: `mock://uploads/${c.id}/${doc.id}`, documentId: doc.id };
});

/** Called by uploadToSignedUrl in mock mode. Simulates progress, then document verification. */
export function mockUpload(uploadUrl, file, onProgress) {
  const [, claimId, docId] = uploadUrl.replace('mock://uploads/', '/').split('/');
  return new Promise((resolve) => {
    let pct = 0;
    const id = setInterval(() => {
      pct = Math.min(100, pct + 12 + Math.random() * 20);
      onProgress?.(Math.round(pct));
      if (pct >= 100) {
        clearInterval(id);
        const claim = state.claims[claimId];
        const doc = claim?.documents.find((d) => d.id === docId);
        if (claim && doc) {
          doc.size = file.size;
          markUploaded(claim, doc, state.users[claim.policyholderId], Date.now());
          scheduleVerification(claim, doc);
        }
        resolve();
      }
    }, 150);
  });
}

/** Live pipeline: verify docs, then eligibility + assessment, then into the review queue. */
function scheduleVerification(claim, doc) {
  setTimeout(() => {
    // Demo hook: a file name containing "blurry" or "unreadable" gets rejected.
    const bad = /blurry|unreadable/i.test(doc.fileName);
    verifyDocument(claim, doc, !bad, bad ? 'The document is not readable. Upload a clear scan.' : null, Date.now());
    if (bad) { setStatus(claim, 'DOCS_PENDING', SERVICES.DOCUMENTS, Date.now()); return; }
    if (!docsComplete(claim) || claim.status !== 'DOCS_PENDING') return;
    setStatus(claim, 'DOCS_VERIFIED', SERVICES.DOCUMENTS, Date.now());
    setTimeout(() => {
      runEligibility(claim, Date.now());
      setTimeout(() => {
        runAssessment(claim, Date.now());
        setTimeout(() => setStatus(claim, 'UNDER_REVIEW', SERVICES.CLAIM_INTAKE, Date.now()), 1500);
      }, 1500);
    }, 2000);
  }, 2500 + Math.random() * 2000);
}

/* ------------------------------------------------- eligibility/assessment */

on('GET', ENDPOINTS.eligibility.get, ({ id, user }) => {
  getClaim(id, user);
  return state.eligibility[id] || fail(404, 'Eligibility has not been checked yet.');
});
on('PUT', ENDPOINTS.eligibility.override, ({ id, user, body }) => {
  requireAdjuster(user);
  const c = getClaim(id, user);
  requireComment(body?.comment);
  if (!state.eligibility[id]) fail(409, 'Eligibility has not been checked yet.');
  if (!REVIEWABLE_STATUSES.has(c.status)) fail(409, 'Eligibility can only be changed before a decision is made.');
  return overrideEligibility(c, body, user, Date.now());
});
on('GET', ENDPOINTS.assessment.get, ({ id, user }) => {
  getClaim(id, user);
  return state.assessments[id] || fail(404, 'The claim has not been assessed yet.');
});
on('PUT', ENDPOINTS.assessment.adjust, ({ id, user, body }) => {
  requireAdjuster(user);
  const c = getClaim(id, user);
  requireComment(body?.comment);
  if (!state.assessments[id]) fail(409, 'The claim has not been assessed yet.');
  if (!REVIEWABLE_STATUSES.has(c.status)) fail(409, 'The amount can only be changed before a decision is made.');
  const amount = Number(body?.amount);
  if (!Number.isFinite(amount) || amount < 0) fail(400, 'Enter a valid amount.');
  if (amount > c.claimedAmount) fail(400, 'The amount cannot exceed the claimed amount.');
  return adjustAssessment(c, { amount, comment: body.comment }, user, Date.now());
});

/* ---------------------------------------------------- approval and 3PC */

on('POST', ENDPOINTS.approval.decide, ({ id, user, body }) => {
  requireAdjuster(user);
  const c = getClaim(id, user);
  requireComment(body?.comment);
  if (!['APPROVED', 'REJECTED'].includes(body?.decision)) fail(400, 'Decision must be APPROVED or REJECTED.');
  if (!REVIEWABLE_STATUSES.has(c.status)) fail(409, `A decision has already been recorded (status ${c.status}).`);
  if (body.decision === 'APPROVED' && c.proposedAmount == null) fail(409, 'The claim has not been assessed yet.');
  decide(c, body, user, Date.now());
  if (body.decision === 'APPROVED') {
    runStepsLive(settlementSteps(c, currentScenario(), { actor: SERVICES.APPROVAL }));
  }
  return c;
});

on('GET', ENDPOINTS.approval.settlement, ({ id, user }) => {
  getClaim(id, user);
  return state.settlements[id] || null;
});

on('POST', ENDPOINTS.approval.retry, ({ id, user }) => {
  requireAdjuster(user);
  const c = getClaim(id, user);
  const prev = state.settlements[id];
  if (!['SETTLEMENT_FAILED', 'APPROVED'].includes(c.status)) fail(409, 'Settlement can only be retried after it has failed.');
  if (prev && prev.outcome === 'IN_PROGRESS') fail(409, 'A settlement is already running.');
  runStepsLive(settlementSteps(c, currentScenario(), { attempt: (prev?.attempt || 0) + 1, actor: user }));
  return { ok: true };
});

/* ----------------------------------------------------------------- audit */

const sortedAudit = () => [...state.audit].sort((a, b) => a.at.localeCompare(b.at));

on('GET', ENDPOINTS.audit.trail, ({ id, user }) => {
  getClaim(id, user);
  return sortedAudit().filter((e) => e.claimId === id);
});
on('GET', ENDPOINTS.audit.search, ({ user, params }) => {
  requireAdjuster(user);
  const from = params.from ? new Date(params.from).getTime() : null;
  const to = params.to ? new Date(params.to).getTime() + 86400000 : null;
  const q = (params.q || '').trim().toLowerCase();
  const items = sortedAudit().reverse().filter((e) => {
    if (params.type && e.type !== params.type) return false;
    const t = new Date(e.at).getTime();
    if (from && t < from) return false;
    if (to && t >= to) return false;
    if (q && !`${e.claimId} ${e.actor.name}`.toLowerCase().includes(q)) return false;
    return true;
  });
  return paginate(items, { page: params.page, size: params.size || 200 });
});

/* ------------------------------------------------------------- analytics */

on('GET', ENDPOINTS.analytics.summary, ({ user }) => { requireAdjuster(user); return analyticsSummary(); });
on('GET', ENDPOINTS.analytics.series, ({ user, params }) => { requireAdjuster(user); return analyticsSeries(params); });
on('GET', ENDPOINTS.analytics.fraudFlags, ({ user }) => { requireAdjuster(user); return fraudFlags(); });

/* ----------------------------------------------------------------- chaos */

on('GET', ENDPOINTS.ops.chaos, ({ user }) => { requireAdjuster(user); return state.chaos; });
on('POST', ENDPOINTS.ops.chaos, ({ user, body }) => {
  requireAdjuster(user);
  const valid = { PAYMENT: ['FAIL', 'OFF'], COORDINATOR: ['CRASH_AFTER_PRECOMMIT', 'OFF'] };
  if (!valid[body?.target]?.includes(body?.mode)) fail(400, 'Invalid chaos target or mode.');
  const entry = state.chaos[body.target];
  const before = entry.mode;
  entry.mode = body.mode;
  entry.changedAt = new Date().toISOString();
  entry.changedBy = publicUser(user);
  if (body.mode !== 'OFF') entry.affectedClaimId = null;
  audit(null, 'CHAOS_TOGGLED', user, Date.now(), { before: { target: body.target, mode: before }, after: { target: body.target, mode: body.mode } });
  return state.chaos;
});
