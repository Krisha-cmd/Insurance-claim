// Sample data for mock mode. Claims are built by running the real engine
// primitives against back-dated timestamps, so every claim has a consistent
// audit trail.

import {
  state, SERVICES, createClaim, addDocument, verifyDocument, runEligibility, runAssessment,
  setStatus, decide, settlementSteps, runStepsNow, runStepsLive,
} from './engine.js';

const MIN = 60000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

const users = [
  { id: 'u-ph-1', username: 'patient1', password: 'password', name: 'Ananya Iyer', role: 'POLICYHOLDER', policyNumber: 'POL-2026-00417' },
  { id: 'u-ph-2', username: 'patient2', password: 'password', name: 'Rohan Mehta', role: 'POLICYHOLDER', policyNumber: 'POL-2026-00852' },
  { id: 'u-adj-1', username: 'adjuster1', password: 'password', name: 'Priya Nair', role: 'ADJUSTER' },
];

const exclusions = [
  { keyword: 'cosmetic', label: 'Cosmetic or aesthetic treatment' },
  { keyword: 'dental', label: 'Dental treatment unless caused by an accident' },
  { keyword: 'infertility', label: 'Infertility and assisted reproduction' },
  { keyword: 'self-inflicted', label: 'Self-inflicted injury' },
  { keyword: 'experimental', label: 'Experimental or unproven treatment' },
];

const policies = [
  {
    policyNumber: 'POL-2026-00417',
    holderId: 'u-ph-1',
    holderName: 'Ananya Iyer',
    plan: 'Family Floater Gold',
    status: 'ACTIVE',
    validFrom: '2025-04-01',
    validTo: '2027-03-31',
    sumInsured: 500000,
    remainingBalance: 500000,
    members: [
      { id: 'm-417-1', name: 'Ananya Iyer', relation: 'Self', dob: '1989-06-12' },
      { id: 'm-417-2', name: 'Karthik Iyer', relation: 'Spouse', dob: '1987-02-03' },
      { id: 'm-417-3', name: 'Meera Iyer', relation: 'Daughter', dob: '2016-09-21' },
    ],
    coverage: {
      deductible: 5000,
      coPayPercent: 10,
      treatments: [
        { treatment: 'Hospitalisation', covered: true, subLimit: 300000, waitingPeriodDays: 30, notes: 'Room rent up to ₹6,000/day' },
        { treatment: 'Surgery', covered: true, subLimit: 250000, waitingPeriodDays: 365, notes: 'Planned surgeries after 1 year' },
        { treatment: 'Day care procedure', covered: true, subLimit: 75000, waitingPeriodDays: 30, notes: '' },
        { treatment: 'Maternity', covered: true, subLimit: 60000, waitingPeriodDays: 730, notes: 'Up to 2 deliveries' },
        { treatment: 'Diagnostics', covered: true, subLimit: 25000, waitingPeriodDays: 30, notes: 'Only when linked to hospitalisation' },
        { treatment: 'Emergency (ambulance)', covered: true, subLimit: 5000, waitingPeriodDays: 0, notes: 'Per event' },
      ],
      exclusions,
    },
  },
  {
    policyNumber: 'POL-2026-00852',
    holderId: 'u-ph-2',
    holderName: 'Rohan Mehta',
    plan: 'Individual Silver',
    status: 'ACTIVE',
    validFrom: '2026-01-10',
    validTo: '2027-01-09',
    sumInsured: 300000,
    remainingBalance: 300000,
    members: [
      { id: 'm-852-1', name: 'Rohan Mehta', relation: 'Self', dob: '1992-11-30' },
      { id: 'm-852-2', name: 'Sunita Mehta', relation: 'Mother', dob: '1961-04-18' },
    ],
    coverage: {
      deductible: 10000,
      coPayPercent: 20,
      treatments: [
        { treatment: 'Hospitalisation', covered: true, subLimit: 200000, waitingPeriodDays: 30, notes: 'Room rent up to ₹4,000/day' },
        { treatment: 'Surgery', covered: true, subLimit: 150000, waitingPeriodDays: 365, notes: 'Planned surgeries after 1 year' },
        { treatment: 'Day care procedure', covered: true, subLimit: 50000, waitingPeriodDays: 30, notes: '' },
        { treatment: 'Maternity', covered: false, subLimit: 0, waitingPeriodDays: 0, notes: 'Not covered on Silver' },
        { treatment: 'Diagnostics', covered: true, subLimit: 15000, waitingPeriodDays: 30, notes: 'Only when linked to hospitalisation' },
        { treatment: 'Emergency (ambulance)', covered: true, subLimit: 3000, waitingPeriodDays: 0, notes: 'Per event' },
      ],
      exclusions,
    },
  },
];

const ALL_DOCS = [
  ['DISCHARGE_SUMMARY', 'discharge-summary.pdf'],
  ['FINAL_BILL', 'final-bill.pdf'],
  ['ID_PROOF', 'aadhaar.pdf'],
  ['PRESCRIPTION', 'prescriptions.pdf'],
];

function isoDate(t) { return new Date(t).toISOString().slice(0, 10); }

/**
 * Build one claim and drive it to `target`.
 * opts: { ago, memberIdx, hospital, diagnosis, treatmentType, amount, stayDays, docs, target, ... }
 */
function build(id, policyNumber, o, now) {
  const policy = state.policies[policyNumber];
  const holder = state.users[policy.holderId];
  const adjuster = state.users['u-adj-1'];
  let t = now - o.ago;
  const unit = o.unit || MIN;
  const tick = (ms = unit) => { t += ms; return t; };

  const admission = t - (o.stayDays + 2) * DAY;
  const claim = createClaim({
    policyNumber,
    patient: { memberId: policy.members[o.memberIdx || 0].id },
    hospital: o.hospital,
    admissionDate: isoDate(admission),
    dischargeDate: isoDate(admission + o.stayDays * DAY),
    diagnosis: o.diagnosis,
    treatmentType: o.treatmentType,
    claimedAmount: o.amount,
  }, holder, t, id);

  if (o.target === 'SUBMITTED') return claim;

  const docs = (o.docs || ALL_DOCS).map(([docType, fileName]) =>
    addDocument(claim, { docType, fileName, contentType: 'application/pdf', size: 180000 + Math.round(Math.random() * 400000) }, holder, tick(), 'PENDING'));
  docs.forEach((d) => {
    const reject = o.rejectDoc === d.docType;
    verifyDocument(claim, d, !reject, reject ? o.rejectReason : null, tick());
  });
  if (o.target === 'DOCS_PENDING') return claim;

  setStatus(claim, 'DOCS_VERIFIED', SERVICES.DOCUMENTS, tick());
  if (o.target === 'DOCS_VERIFIED') return claim;

  runEligibility(claim, tick());
  if (o.target === 'ELIGIBILITY_CHECKED') return claim;

  runAssessment(claim, tick());
  if (o.target === 'ASSESSED') return claim;

  setStatus(claim, 'UNDER_REVIEW', SERVICES.CLAIM_INTAKE, tick());
  if (o.target === 'UNDER_REVIEW') return claim;

  const decision = o.target === 'REJECTED' ? 'REJECTED' : 'APPROVED';
  decide(claim, { decision, comment: o.decisionComment }, adjuster, tick(3 * HOUR));
  if (o.target === 'APPROVED' || o.target === 'REJECTED') return claim;

  if (o.target === 'SETTLING') {
    // Keep this one visibly in flight: it waits on the payment gateway's DoCommit ack for ~90s.
    runStepsLive(settlementSteps(claim, 'OK', { stepMs: 700, slowPaymentAckMs: 90000 }));
    return claim;
  }
  const scenario = o.target === 'SETTLEMENT_FAILED' ? 'PAYMENT_NO' : 'OK';
  runStepsNow(settlementSteps(claim, scenario, { stepMs: 400 }), tick());
  return claim;
}

export function seed() {
  const now = Date.now();
  users.forEach((u) => { state.users[u.id] = { ...u }; });
  policies.forEach((p) => { state.policies[p.policyNumber] = structuredClone(p); });

  const A = 'POL-2026-00417';
  const R = 'POL-2026-00852';

  // Oldest first so ids and event order read naturally.
  build('CLM-24011', A, { ago: 20 * DAY, memberIdx: 2, hospital: 'Rainbow Children’s Hospital, Bengaluru', diagnosis: 'Acute bronchiolitis', treatmentType: 'Hospitalisation', amount: 54200, stayDays: 3, target: 'SETTLED', decisionComment: 'All documents in order; within sub-limit.' }, now);
  build('CLM-24009', A, { ago: 10 * DAY, memberIdx: 1, hospital: 'Apollo Hospitals, Chennai', diagnosis: 'Cosmetic rhinoplasty', treatmentType: 'Surgery', amount: 85000, stayDays: 1, target: 'REJECTED', decisionComment: 'Cosmetic procedures are excluded under section 4.2 of the policy wording.' }, now);
  build('CLM-24007', R, { ago: 5 * DAY, memberIdx: 1, hospital: 'Kokilaben Dhirubhai Ambani Hospital, Mumbai', diagnosis: 'Bilateral cataract', treatmentType: 'Surgery', amount: 180000, stayDays: 2, target: 'UNDER_REVIEW' }, now);
  build('CLM-24006', A, { ago: 3 * DAY, memberIdx: 0, hospital: 'Manipal Hospital, Bengaluru', diagnosis: 'Dengue fever with thrombocytopenia', treatmentType: 'Hospitalisation', amount: 142000, stayDays: 5, target: 'UNDER_REVIEW' }, now);
  build('CLM-24002', A, { ago: 2 * DAY, memberIdx: 0, hospital: 'Fortis Hospital, Bengaluru', diagnosis: 'Appendicitis (laparoscopic appendectomy)', treatmentType: 'Hospitalisation', amount: 68500, stayDays: 2, target: 'DOCS_PENDING', rejectDoc: 'FINAL_BILL', rejectReason: 'The bill is not itemised. Upload the itemised final bill with the hospital seal.' }, now);
  build('CLM-24012', A, { ago: 1 * DAY + 4 * HOUR, memberIdx: 1, hospital: 'Narayana Health City, Bengaluru', diagnosis: 'Coronary angioplasty', treatmentType: 'Hospitalisation', amount: 215000, stayDays: 4, target: 'SETTLEMENT_FAILED', decisionComment: 'Verified with treating cardiologist; approve proposed amount.' }, now);
  build('CLM-24005', R, { ago: 1 * DAY, memberIdx: 0, hospital: 'Lilavati Hospital, Mumbai', diagnosis: 'Kidney stone (ureteroscopy)', treatmentType: 'Day care procedure', amount: 115000, stayDays: 1, target: 'ASSESSED' }, now);
  build('CLM-24008', R, { ago: 9 * HOUR, memberIdx: 0, hospital: 'Jaslok Hospital, Mumbai', diagnosis: 'Viral gastroenteritis', treatmentType: 'Hospitalisation', amount: 47500, stayDays: 2, target: 'APPROVED', decisionComment: 'Short stay, standard treatment. Approving proposed amount.' }, now);
  build('CLM-24010', R, { ago: 3 * HOUR + 20 * MIN, memberIdx: 1, hospital: 'Hinduja Hospital, Mumbai', diagnosis: 'Pneumonia', treatmentType: 'Hospitalisation', amount: 92300, stayDays: 4, target: 'SETTLING', decisionComment: 'Documents verified, amount within sub-limit.' }, now);
  // Three claims on one policy within 10 minutes: shows up as a fraud flag.
  build('CLM-24004', R, { unit: 20000, ago: 9 * MIN, memberIdx: 0, hospital: 'Nanavati Max Hospital, Mumbai', diagnosis: 'Fractured wrist', treatmentType: 'Day care procedure', amount: 61000, stayDays: 1, target: 'ELIGIBILITY_CHECKED' }, now);
  build('CLM-24003', R, { unit: 20000, ago: 7 * MIN, memberIdx: 0, hospital: 'Nanavati Max Hospital, Mumbai', diagnosis: 'Fractured wrist follow-up imaging', treatmentType: 'Diagnostics', amount: 22500, stayDays: 1, target: 'DOCS_VERIFIED' }, now);
  build('CLM-24001', R, { unit: 20000, ago: 4 * MIN, memberIdx: 0, hospital: 'Nanavati Max Hospital, Mumbai', diagnosis: 'Physiotherapy after fracture', treatmentType: 'Day care procedure', amount: 38000, stayDays: 1, target: 'SUBMITTED' }, now);

  state.audit.sort((a, b) => a.at.localeCompare(b.at));
}
