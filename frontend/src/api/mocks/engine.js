// In-memory claim engine for mock mode. Every state change goes through these
// primitives, which also write audit events, so the seeded history and the live
// clicks produce the same shape of data.

import { DOC_TYPES } from '../../lib/status.js';

export const SERVICES = {
  CLAIM_INTAKE: { id: 'svc-claim-intake', name: 'Claim Intake service', role: 'SYSTEM' },
  DOCUMENTS:    { id: 'svc-doc-verification', name: 'Document Verification service', role: 'SYSTEM' },
  ELIGIBILITY:  { id: 'svc-eligibility', name: 'Eligibility service', role: 'SYSTEM' },
  ASSESSMENT:   { id: 'svc-assessment', name: 'Assessment service', role: 'SYSTEM' },
  APPROVAL:     { id: 'svc-approval', name: 'Approval service (3PC coordinator)', role: 'SYSTEM' },
  OPS:          { id: 'svc-ops', name: 'Ops console', role: 'SYSTEM' },
};

export const PARTICIPANTS = ['APPROVAL', 'CLAIM_INTAKE', 'POLICY', 'PAYMENT'];

export const state = {
  users: {},
  policies: {},
  claims: {},
  eligibility: {},
  assessments: {},
  settlements: {},
  audit: [],
  chaos: {
    PAYMENT: { mode: 'OFF', changedAt: null, changedBy: null, affectedClaimId: null },
    COORDINATOR: { mode: 'OFF', changedAt: null, changedBy: null, affectedClaimId: null },
  },
  seq: { claim: 24012, doc: 900, event: 1, tx: 7000 },
};

const iso = (t) => new Date(t).toISOString();
const actorOf = (u) => ({ id: u.id, name: u.name, role: u.role });

export function audit(claimId, type, actor, at, { before = null, after = null, comment = null } = {}) {
  const ev = {
    id: `EVT-${String(state.seq.event++).padStart(5, '0')}`,
    claimId, type, actor: actorOf(actor), at: iso(at), before, after, comment,
  };
  state.audit.push(ev);
  return ev;
}

export function setStatus(claim, status, actor, at, comment) {
  const before = claim.status;
  if (before === status) return;
  claim.status = status;
  claim.updatedAt = iso(at);
  audit(claim.id, 'STATUS_CHANGED', actor, at, { before: { status: before }, after: { status }, comment });
}

/* ------------------------------------------------------------------ claims */

export function createClaim(input, user, at, id) {
  const policy = state.policies[input.policyNumber];
  const member = policy.members.find((m) => m.id === input.patient?.memberId) || policy.members[0];
  const claimId = id || `CLM-${++state.seq.claim}`;
  const claim = {
    id: claimId,
    policyNumber: policy.policyNumber,
    policyholderId: policy.holderId,
    policyholderName: policy.holderName,
    patient: { memberId: member.id, name: member.name, relation: member.relation },
    hospital: input.hospital,
    admissionDate: input.admissionDate,
    dischargeDate: input.dischargeDate,
    diagnosis: input.diagnosis,
    treatmentType: input.treatmentType,
    claimedAmount: Number(input.claimedAmount),
    proposedAmount: null,
    approvedAmount: null,
    eligibility: null,
    status: 'SUBMITTED',
    rejectionReason: null,
    paymentReference: null,
    documents: [],
    createdAt: iso(at),
    updatedAt: iso(at),
  };
  state.claims[claimId] = claim;
  audit(claimId, 'CLAIM_SUBMITTED', user, at, {
    after: { status: 'SUBMITTED', claimedAmount: claim.claimedAmount, hospital: claim.hospital },
  });
  return claim;
}

export function addDocument(claim, { docType, fileName, contentType, size }, user, at, status = 'AWAITING_UPLOAD') {
  const doc = {
    id: `DOC-${++state.seq.doc}`,
    docType, fileName, contentType: contentType || 'application/pdf', size: size || 0,
    status, reason: null, uploadedAt: iso(at), verifiedAt: null,
    previewUrl: null,
  };
  // A re-upload replaces the earlier rejected document of the same type.
  claim.documents = claim.documents.filter((d) => !(d.docType === docType && d.status === 'REJECTED'));
  claim.documents.push(doc);
  if (status !== 'AWAITING_UPLOAD') markUploaded(claim, doc, user, at);
  return doc;
}

export function markUploaded(claim, doc, user, at) {
  doc.status = 'PENDING';
  doc.uploadedAt = iso(at);
  audit(claim.id, 'DOCUMENT_UPLOADED', user, at, { after: { documentId: doc.id, docType: doc.docType, fileName: doc.fileName } });
  if (claim.status === 'SUBMITTED' || claim.status === 'DOCS_VERIFIED') setStatus(claim, 'DOCS_PENDING', SERVICES.CLAIM_INTAKE, at);
}

export function verifyDocument(claim, doc, ok, reason, at) {
  doc.status = ok ? 'VERIFIED' : 'REJECTED';
  doc.reason = ok ? null : reason;
  doc.verifiedAt = iso(at);
  audit(claim.id, ok ? 'DOCUMENT_VERIFIED' : 'DOCUMENT_REJECTED', SERVICES.DOCUMENTS, at, {
    after: { documentId: doc.id, docType: doc.docType, result: doc.status, reason: doc.reason },
  });
}

export function docsComplete(claim) {
  if (claim.documents.some((d) => d.status === 'PENDING' || d.status === 'AWAITING_UPLOAD')) return false;
  return DOC_TYPES.filter((t) => t.required)
    .every((t) => claim.documents.some((d) => d.docType === t.value && d.status === 'VERIFIED'));
}

/* ------------------------------------------------------------- eligibility */

const DAY = 86400000;

export function runEligibility(claim, at) {
  const policy = state.policies[claim.policyNumber];
  const admission = new Date(claim.admissionDate).getTime();
  const cov = policy.coverage.treatments.find((t) => t.treatment === claim.treatmentType);
  const excluded = policy.coverage.exclusions.find((e) => claim.diagnosis.toLowerCase().includes(e.keyword));
  const waitingOk = cov ? admission >= new Date(policy.validFrom).getTime() + cov.waitingPeriodDays * DAY : false;

  const checks = [
    {
      rule: 'POLICY_ACTIVE', label: 'Policy active on admission date',
      passed: admission >= new Date(policy.validFrom).getTime() && admission <= new Date(policy.validTo).getTime(),
      detail: `Valid ${policy.validFrom} to ${policy.validTo}`,
    },
    {
      rule: 'MEMBER_COVERED', label: 'Patient is a member of the policy',
      passed: policy.members.some((m) => m.id === claim.patient.memberId),
      detail: `${claim.patient.name} (${claim.patient.relation})`,
    },
    {
      rule: 'TREATMENT_COVERED', label: 'Treatment type is covered',
      passed: Boolean(cov && cov.covered),
      detail: cov ? (cov.covered ? `${cov.treatment} is covered` : `${cov.treatment} is not covered on this plan`) : 'Unknown treatment',
    },
    {
      rule: 'WAITING_PERIOD', label: 'Waiting period completed',
      passed: waitingOk,
      detail: cov ? `${cov.waitingPeriodDays} day waiting period from ${policy.validFrom}` : '—',
    },
    {
      rule: 'NOT_EXCLUDED', label: 'Diagnosis is not an exclusion',
      passed: !excluded,
      detail: excluded ? `Matches exclusion: ${excluded.label}` : 'No exclusion matched',
    },
    {
      rule: 'SUM_INSURED', label: 'Claimed amount within remaining sum insured',
      passed: claim.claimedAmount <= policy.remainingBalance,
      detail: `Remaining balance ₹${policy.remainingBalance.toLocaleString('en-IN')}`,
    },
  ];
  const eligible = checks.every((c) => c.passed);
  state.eligibility[claim.id] = {
    claimId: claim.id, eligible, checks, evaluatedAt: iso(at),
    overridden: false, override: null,
  };
  claim.eligibility = eligible ? 'ELIGIBLE' : 'INELIGIBLE';
  audit(claim.id, 'ELIGIBILITY_CHECKED', SERVICES.ELIGIBILITY, at, {
    after: { eligible, failedRules: checks.filter((c) => !c.passed).map((c) => c.rule) },
  });
  setStatus(claim, 'ELIGIBILITY_CHECKED', SERVICES.ELIGIBILITY, at);
}

export function overrideEligibility(claim, { eligible, reason, comment }, user, at) {
  const el = state.eligibility[claim.id];
  const before = { eligible: el.eligible };
  el.eligible = Boolean(eligible);
  el.overridden = true;
  el.override = { eligible: el.eligible, reason, comment, by: actorOf(user), at: iso(at) };
  claim.eligibility = el.eligible ? 'ELIGIBLE' : 'INELIGIBLE';
  claim.updatedAt = iso(at);
  audit(claim.id, 'ELIGIBILITY_OVERRIDDEN', user, at, { before, after: { eligible: el.eligible, reason }, comment });
  return el;
}

/* -------------------------------------------------------------- assessment */

export function runAssessment(claim, at) {
  const policy = state.policies[claim.policyNumber];
  const cov = policy.coverage.treatments.find((t) => t.treatment === claim.treatmentType);
  const subLimit = cov?.subLimit ?? policy.sumInsured;
  const capped = Math.min(claim.claimedAmount, subLimit, policy.remainingBalance);
  const deductible = Math.min(policy.coverage.deductible, capped);
  const afterDeductible = capped - deductible;
  const coPay = Math.round((afterDeductible * policy.coverage.coPayPercent) / 100);
  const proposed = afterDeductible - coPay;
  state.assessments[claim.id] = {
    claimId: claim.id,
    claimedAmount: claim.claimedAmount,
    subLimit,
    cappedAmount: capped,
    deductible,
    afterDeductible,
    coPayPercent: policy.coverage.coPayPercent,
    coPayAmount: coPay,
    calculatedAmount: proposed,
    proposedAmount: proposed,
    adjustments: [],
    assessedAt: iso(at),
  };
  claim.proposedAmount = proposed;
  audit(claim.id, 'ASSESSMENT_COMPLETED', SERVICES.ASSESSMENT, at, { after: { proposedAmount: proposed } });
  setStatus(claim, 'ASSESSED', SERVICES.ASSESSMENT, at);
}

export function adjustAssessment(claim, { amount, comment }, user, at) {
  const a = state.assessments[claim.id];
  const before = { proposedAmount: a.proposedAmount };
  a.proposedAmount = Number(amount);
  a.adjustments.push({ from: before.proposedAmount, to: a.proposedAmount, comment, by: actorOf(user), at: iso(at) });
  claim.proposedAmount = a.proposedAmount;
  claim.updatedAt = iso(at);
  audit(claim.id, 'ASSESSMENT_ADJUSTED', user, at, { before, after: { proposedAmount: a.proposedAmount }, comment });
  return a;
}

/* ---------------------------------------------------------------- decision */

export function decide(claim, { decision, comment }, user, at) {
  if (decision === 'APPROVED') {
    claim.approvedAmount = claim.proposedAmount;
    audit(claim.id, 'CLAIM_APPROVED', user, at, { before: { status: claim.status }, after: { decision, approvedAmount: claim.approvedAmount }, comment });
    setStatus(claim, 'APPROVED', SERVICES.APPROVAL, at);
  } else {
    claim.rejectionReason = comment;
    audit(claim.id, 'CLAIM_REJECTED', user, at, { before: { status: claim.status }, after: { decision }, comment });
    setStatus(claim, 'REJECTED', SERVICES.APPROVAL, at);
  }
}

/* ------------------------------------------------------- 3PC settlement */

/**
 * Builds the ordered steps of one 3PC run. Each step is {delay, run(at)}.
 * `scenario` is 'OK' | 'PAYMENT_NO' | 'COORDINATOR_CRASH'. `stepMs` controls pacing;
 * `slowPaymentAckMs` lets the seed keep one settlement visibly in flight.
 */
export function settlementSteps(claim, scenario, { stepMs = 900, slowPaymentAckMs = 0, attempt = 1, actor = SERVICES.APPROVAL } = {}) {
  const txId = `TX-${++state.seq.tx}`;
  const steps = [];
  const step = (delay, run) => steps.push({ delay, run });
  let tx;

  const log = (at, message, level = 'info') => tx.log.push({ at: iso(at), message, level });

  step(0, (at) => {
    tx = {
      transactionId: txId, claimId: claim.id, attempt,
      phase: 'CAN_COMMIT', outcome: 'IN_PROGRESS', coordinator: 'UP',
      amount: claim.approvedAmount,
      participants: PARTICIPANTS.map((name) => ({ name, vote: 'PENDING', preCommitAck: false, doCommitAck: false, aborted: false })),
      startedAt: iso(at), finishedAt: null, abortReason: null, log: [],
    };
    state.settlements[claim.id] = tx;
    if (claim.status !== 'SETTLING') setStatus(claim, 'SETTLING', actor, at);
    audit(claim.id, attempt > 1 ? 'SETTLEMENT_RETRIED' : 'SETTLEMENT_STARTED', actor, at, { after: { transactionId: txId, attempt, amount: claim.approvedAmount } });
    log(at, `Coordinator wrote BEGIN ${txId} to its log and sent CanCommit to ${PARTICIPANTS.length} participants`);
  });

  const p = (name) => tx.participants.find((x) => x.name === name);

  // Phase 1: CanCommit votes
  for (const name of PARTICIPANTS) {
    step(stepMs, (at) => {
      if (name === 'PAYMENT' && scenario === 'PAYMENT_NO') {
        p(name).vote = 'NO';
        log(at, 'PAYMENT voted NO: payment gateway unavailable', 'error');
      } else {
        p(name).vote = 'YES';
        log(at, `${name} voted YES`);
      }
    });
  }

  if (scenario === 'PAYMENT_NO') {
    step(stepMs, (at) => {
      tx.outcome = 'ABORTED';
      tx.abortReason = 'Payment participant voted NO in CanCommit (payment gateway unavailable).';
      tx.participants.forEach((x) => { x.aborted = true; });
      tx.finishedAt = iso(at);
      log(at, 'Coordinator logged ABORT and sent Abort to all participants', 'error');
      audit(claim.id, 'SETTLEMENT_ABORTED', SERVICES.APPROVAL, at, { after: { transactionId: txId, reason: tx.abortReason } });
      setStatus(claim, 'SETTLEMENT_FAILED', SERVICES.APPROVAL, at);
      if (state.chaos.PAYMENT.mode === 'FAIL') state.chaos.PAYMENT.affectedClaimId = claim.id;
    });
    return steps;
  }

  // Phase 2: PreCommit
  step(stepMs, (at) => { tx.phase = 'PRE_COMMIT'; log(at, 'All participants voted YES. Coordinator logged PRE_COMMIT and sent PreCommit'); });
  for (const name of PARTICIPANTS) {
    step(stepMs, (at) => { p(name).preCommitAck = true; log(at, `${name} acknowledged PreCommit`); });
  }

  if (scenario === 'COORDINATOR_CRASH') {
    step(stepMs, (at) => {
      tx.coordinator = 'DOWN';
      log(at, 'Coordinator crashed after PreCommit (chaos)', 'error');
      audit(claim.id, 'COORDINATOR_CRASHED', SERVICES.OPS, at, { after: { transactionId: txId, phase: 'PRE_COMMIT' } });
      if (state.chaos.COORDINATOR.mode === 'CRASH_AFTER_PRECOMMIT') state.chaos.COORDINATOR.affectedClaimId = claim.id;
    });
    step(stepMs * 4, (at) => {
      tx.coordinator = 'RECOVERING';
      log(at, 'Coordinator restarted, read PRE_COMMIT for this transaction from its log and resumed with DoCommit', 'warn');
      audit(claim.id, 'COORDINATOR_RECOVERED', SERVICES.APPROVAL, at, { after: { transactionId: txId, resumedFrom: 'PRE_COMMIT' } });
    });
  }

  // Phase 3: DoCommit
  step(stepMs, (at) => { tx.phase = 'DO_COMMIT'; if (tx.coordinator !== 'DOWN') tx.coordinator = 'UP'; log(at, 'Coordinator logged DO_COMMIT and sent DoCommit'); });
  for (const name of PARTICIPANTS) {
    const delay = name === 'PAYMENT' && slowPaymentAckMs ? slowPaymentAckMs : stepMs;
    step(delay, (at) => { p(name).doCommitAck = true; log(at, `${name} committed and acknowledged`); });
  }
  step(stepMs, (at) => {
    tx.outcome = 'COMMITTED';
    tx.phase = 'DONE';
    tx.finishedAt = iso(at);
    claim.paymentReference = `UTR${String(at).slice(-10)}`;
    const policy = state.policies[claim.policyNumber];
    policy.remainingBalance = Math.max(0, policy.remainingBalance - claim.approvedAmount);
    log(at, `Transaction committed. Payment reference ${claim.paymentReference}`);
    audit(claim.id, 'SETTLEMENT_COMMITTED', SERVICES.APPROVAL, at, { after: { transactionId: txId, paymentReference: claim.paymentReference, amount: claim.approvedAmount } });
    setStatus(claim, 'SETTLED', SERVICES.APPROVAL, at);
  });
  return steps;
}

/** Run steps instantly with back-dated timestamps (seeding). */
export function runStepsNow(steps, startAt) {
  let t = startAt;
  for (const s of steps) { t += s.delay; s.run(t); }
}

/** Run steps in real time (live mock). */
export function runStepsLive(steps) {
  let i = 0;
  const next = () => {
    if (i >= steps.length) return;
    const s = steps[i++];
    setTimeout(() => { s.run(Date.now()); next(); }, s.delay);
  };
  next();
}

export function currentScenario() {
  if (state.chaos.PAYMENT.mode === 'FAIL') return 'PAYMENT_NO';
  if (state.chaos.COORDINATOR.mode === 'CRASH_AFTER_PRECOMMIT') return 'COORDINATOR_CRASH';
  return 'OK';
}
