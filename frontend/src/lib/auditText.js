import { STATUS_META, docTypeLabel } from './status.js';
import { money } from './format.js';

const statusLabel = (s) => STATUS_META[s]?.label ?? s;

export const AUDIT_TYPES = [
  'CLAIM_SUBMITTED', 'DOCUMENT_UPLOADED', 'DOCUMENT_VERIFIED', 'DOCUMENT_REJECTED', 'STATUS_CHANGED',
  'ELIGIBILITY_CHECKED', 'ELIGIBILITY_OVERRIDDEN', 'ASSESSMENT_COMPLETED', 'ASSESSMENT_ADJUSTED',
  'CLAIM_APPROVED', 'CLAIM_REJECTED', 'SETTLEMENT_STARTED', 'SETTLEMENT_RETRIED', 'SETTLEMENT_COMMITTED',
  'SETTLEMENT_ABORTED', 'COORDINATOR_CRASHED', 'COORDINATOR_RECOVERED', 'CHAOS_TOGGLED',
];

/** Turn an audit event into { title, description, tone } for timelines and tables. */
export function describeEvent(e) {
  const a = e.after || {};
  const b = e.before || {};
  switch (e.type) {
    case 'CLAIM_SUBMITTED': return { title: 'Claim submitted', description: `Claimed ${money(a.claimedAmount)} at ${a.hospital}`, tone: 'info' };
    case 'DOCUMENT_UPLOADED': return { title: `${docTypeLabel(a.docType)} uploaded`, description: a.fileName, tone: 'neutral' };
    case 'DOCUMENT_VERIFIED': return { title: `${docTypeLabel(a.docType)} verified`, tone: 'success' };
    case 'DOCUMENT_REJECTED': return { title: `${docTypeLabel(a.docType)} rejected`, description: a.reason, tone: 'danger' };
    case 'STATUS_CHANGED': return { title: statusLabel(a.status), description: `Status changed from ${statusLabel(b.status)}`, tone: STATUS_META[a.status]?.tone === 'danger' ? 'danger' : STATUS_META[a.status]?.tone === 'success' ? 'success' : 'neutral' };
    case 'ELIGIBILITY_CHECKED': return { title: a.eligible ? 'Eligibility confirmed' : 'Eligibility check failed', description: a.failedRules?.length ? `Failed: ${a.failedRules.join(', ')}` : 'All coverage rules passed', tone: a.eligible ? 'success' : 'warning' };
    case 'ELIGIBILITY_OVERRIDDEN': return { title: `Eligibility overridden to ${a.eligible ? 'eligible' : 'not eligible'}`, description: a.reason, tone: 'warning' };
    case 'ASSESSMENT_COMPLETED': return { title: 'Amount assessed', description: `Proposed ${money(a.proposedAmount)}`, tone: 'info' };
    case 'ASSESSMENT_ADJUSTED': return { title: 'Assessed amount adjusted', description: `${money(b.proposedAmount)} → ${money(a.proposedAmount)}`, tone: 'warning' };
    case 'CLAIM_APPROVED': return { title: 'Claim approved', description: `Approved ${money(a.approvedAmount)}`, tone: 'success' };
    case 'CLAIM_REJECTED': return { title: 'Claim rejected', tone: 'danger' };
    case 'SETTLEMENT_STARTED': return { title: 'Settlement started', description: `Transaction ${a.transactionId}`, tone: 'info' };
    case 'SETTLEMENT_RETRIED': return { title: `Settlement retried (attempt ${a.attempt})`, description: `Transaction ${a.transactionId}`, tone: 'info' };
    case 'SETTLEMENT_COMMITTED': return { title: 'Payment settled', description: `Reference ${a.paymentReference}`, tone: 'success' };
    case 'SETTLEMENT_ABORTED': return { title: 'Settlement aborted', description: a.reason, tone: 'danger' };
    case 'COORDINATOR_CRASHED': return { title: 'Coordinator crashed', description: `During ${a.phase} of ${a.transactionId}`, tone: 'danger' };
    case 'COORDINATOR_RECOVERED': return { title: 'Coordinator recovered', description: `Resumed from ${a.resumedFrom}`, tone: 'warning' };
    case 'CHAOS_TOGGLED': return { title: `Chaos: ${a.target} → ${a.mode}`, tone: 'warning' };
    default: return { title: e.type, tone: 'neutral' };
  }
}

/** Events a policyholder cares about (hides internal 3PC / chaos noise). */
const POLICYHOLDER_TYPES = new Set([
  'CLAIM_SUBMITTED', 'DOCUMENT_UPLOADED', 'DOCUMENT_VERIFIED', 'DOCUMENT_REJECTED', 'STATUS_CHANGED',
  'CLAIM_APPROVED', 'CLAIM_REJECTED', 'SETTLEMENT_COMMITTED',
]);
export const isPolicyholderEvent = (e) => POLICYHOLDER_TYPES.has(e.type);
