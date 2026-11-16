export const CLAIM_STATUS = Object.freeze({
  SUBMITTED: 'SUBMITTED',
  DOCS_PENDING: 'DOCS_PENDING',
  DOCS_VERIFIED: 'DOCS_VERIFIED',
  ELIGIBILITY_CHECKED: 'ELIGIBILITY_CHECKED',
  ASSESSED: 'ASSESSED',
  UNDER_REVIEW: 'UNDER_REVIEW',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
  SETTLING: 'SETTLING',
  SETTLED: 'SETTLED',
  SETTLEMENT_FAILED: 'SETTLEMENT_FAILED',
});

export const STATUS_LIST = Object.values(CLAIM_STATUS);

/** Label + tone + glyph. Tone drives colour; the label and glyph mean colour is never the only signal. */
export const STATUS_META = {
  SUBMITTED:           { label: 'Submitted',           tone: 'neutral', glyph: '○' },
  DOCS_PENDING:        { label: 'Documents pending',   tone: 'info',    glyph: '◔' },
  DOCS_VERIFIED:       { label: 'Documents verified',  tone: 'info',    glyph: '◑' },
  ELIGIBILITY_CHECKED: { label: 'Eligibility checked', tone: 'info',    glyph: '◑' },
  ASSESSED:            { label: 'Assessed',            tone: 'info',    glyph: '◕' },
  UNDER_REVIEW:        { label: 'Under review',        tone: 'warning', glyph: '◕' },
  APPROVED:            { label: 'Approved',            tone: 'success', glyph: '✓' },
  REJECTED:            { label: 'Rejected',            tone: 'danger',  glyph: '✕' },
  SETTLING:            { label: 'Settling',            tone: 'warning', glyph: '⇄' },
  SETTLED:             { label: 'Settled',             tone: 'success', glyph: '✓' },
  SETTLEMENT_FAILED:   { label: 'Settlement failed',   tone: 'danger',  glyph: '!' },
};

/** States from which nothing more will happen without human action. Polling stops here. */
export const FINAL_STATUSES = new Set(['REJECTED', 'SETTLED']);

export const REVIEWABLE_STATUSES = new Set(['ELIGIBILITY_CHECKED', 'ASSESSED', 'UNDER_REVIEW']);

export const DOC_TYPES = [
  { value: 'DISCHARGE_SUMMARY', label: 'Discharge summary', required: true },
  { value: 'FINAL_BILL',        label: 'Final hospital bill', required: true },
  { value: 'PRESCRIPTION',      label: 'Prescriptions', required: false },
  { value: 'LAB_REPORT',        label: 'Lab reports', required: false },
  { value: 'ID_PROOF',          label: 'ID proof', required: true },
];

export const docTypeLabel = (v) => DOC_TYPES.find((d) => d.value === v)?.label ?? v;

export const TREATMENT_TYPES = [
  'Hospitalisation', 'Surgery', 'Day care procedure', 'Maternity', 'Diagnostics', 'Emergency (ambulance)',
];
