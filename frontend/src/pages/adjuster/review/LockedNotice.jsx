import { REVIEWABLE_STATUSES, STATUS_META } from '../../../lib/status.js';

export const isEditable = (claim) => REVIEWABLE_STATUSES.has(claim.status);

export default function LockedNotice({ claim, what = 'Changes' }) {
  if (isEditable(claim)) return null;
  const before = ['SUBMITTED', 'DOCS_PENDING', 'DOCS_VERIFIED'].includes(claim.status);
  return (
    <div className="notice info" role="note">
      {before
        ? `${what} open once eligibility has been checked. The claim is currently “${STATUS_META[claim.status]?.label}”.`
        : `${what} are locked because a decision has been recorded (${STATUS_META[claim.status]?.label}).`}
    </div>
  );
}
