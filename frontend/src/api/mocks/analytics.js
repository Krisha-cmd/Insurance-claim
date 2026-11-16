// Mock stream-processor output: 1-minute tumbling windows over the last 30 minutes.
// A deterministic synthetic baseline (stable across refreshes) plus real events
// from the in-memory audit log, so actions taken in the demo show up in the charts.

import { state } from './engine.js';

const MIN = 60000;

function rand(seed) {
  // mulberry32
  let t = (seed + 0x6d2b79f5) | 0;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}

function baseline(windowStart) {
  const k = Math.floor(windowStart / MIN);
  const r = (i) => rand(k * 7 + i);
  const submitted = 3 + Math.floor(r(1) * 7);
  const approved = 1 + Math.floor(r(2) * 4);
  const rejected = Math.floor(r(3) * 2.2);
  const settled = Math.max(0, approved - (r(4) > 0.7 ? 1 : 0));
  const aborts = r(5) > 0.82 ? 1 + Math.floor(r(6) * 2) : 0;
  const claimedTotal = submitted * (55000 + Math.floor(r(8) * 60000));
  return { submitted, approved, rejected, settled, aborts, commits: settled, claimedTotal };
}

function windows(count = 30) {
  const end = Math.floor(Date.now() / MIN) * MIN; // start of the current (open) window
  const out = [];
  for (let i = count; i >= 1; i--) {
    const start = end - i * MIN;
    const w = { windowStart: new Date(start).toISOString(), windowEnd: new Date(start + MIN).toISOString(), ...baseline(start) };
    for (const e of state.audit) {
      const t = new Date(e.at).getTime();
      if (t < start || t >= start + MIN) continue;
      if (e.type === 'CLAIM_SUBMITTED') {
        w.submitted += 1;
        w.claimedTotal += state.claims[e.claimId]?.claimedAmount || 0;
      }
      if (e.type === 'CLAIM_APPROVED') w.approved += 1;
      if (e.type === 'CLAIM_REJECTED') w.rejected += 1;
      if (e.type === 'SETTLEMENT_COMMITTED') { w.settled += 1; w.commits += 1; }
      if (e.type === 'SETTLEMENT_ABORTED') w.aborts += 1;
    }
    out.push(w);
  }
  return out;
}

export function analyticsSummary() {
  const ws = windows(30);
  const last = ws[ws.length - 1];
  const sum = (k) => ws.reduce((a, w) => a + w[k], 0);
  const decided = sum('approved') + sum('rejected');
  return {
    windowStart: last.windowStart,
    windowEnd: last.windowEnd,
    claimsLastMinute: last.submitted,
    approvalRate: decided ? sum('approved') / decided : null,
    avgClaimedAmount: sum('submitted') ? Math.round(sum('claimedTotal') / sum('submitted')) : null,
    settlementAborts: sum('aborts'),
    settlementCommits: sum('commits'),
    lookbackMinutes: 30,
    generatedAt: new Date().toISOString(),
  };
}

export function analyticsSeries({ metric = 'claims_by_status' }) {
  const ws = windows(30);
  const points = ws.map((w) => (metric === 'aborts'
    ? { windowStart: w.windowStart, aborts: w.aborts, commits: w.commits }
    : { windowStart: w.windowStart, submitted: w.submitted, approved: w.approved, rejected: w.rejected, settled: w.settled }));
  return { metric, window: '1m', points };
}

export function fraudFlags() {
  const TEN = 10 * MIN;
  const byPolicy = {};
  Object.values(state.claims).forEach((c) => { (byPolicy[c.policyNumber] ||= []).push(c); });
  const flags = [];
  for (const [policyNumber, claims] of Object.entries(byPolicy)) {
    const sorted = claims.map((c) => ({ id: c.id, t: new Date(c.createdAt).getTime() })).sort((a, b) => a.t - b.t);
    let best = null;
    for (let i = 0; i < sorted.length; i++) {
      const inWin = sorted.filter((x) => x.t >= sorted[i].t && x.t < sorted[i].t + TEN);
      if (inWin.length > 2 && (!best || inWin.length > best.length)) best = inWin;
    }
    if (best) {
      const p = state.policies[policyNumber];
      flags.push({
        policyNumber,
        holderName: p.holderName,
        claimCount: best.length,
        firstClaimAt: new Date(best[0].t).toISOString(),
        lastClaimAt: new Date(best[best.length - 1].t).toISOString(),
        claimIds: best.map((x) => x.id),
      });
    }
  }
  return flags.sort((a, b) => b.lastClaimAt.localeCompare(a.lastClaimAt));
}
