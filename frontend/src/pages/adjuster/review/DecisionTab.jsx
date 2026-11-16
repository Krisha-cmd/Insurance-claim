import { useState } from 'react';
import { approvalApi } from '../../../api/services.js';
import { money } from '../../../lib/format.js';
import { useToast } from '../../../components/Toast.jsx';
import Card from '../../../components/Card.jsx';
import Button from '../../../components/Button.jsx';
import CommentField, { commentValid } from '../../../components/CommentField.jsx';
import LockedNotice, { isEditable } from './LockedNotice.jsx';
import s from './review.module.css';

export default function DecisionTab({ claim, onClaimChanged, goTo }) {
  const toast = useToast();
  const [decision, setDecision] = useState(null);
  const [comment, setComment] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const editable = isEditable(claim);
  const canContinue = decision && commentValid(comment) && (decision === 'REJECTED' || claim.proposedAmount != null);

  const submit = async () => {
    setBusy(true); setError(null);
    try {
      await approvalApi.decide(claim.id, { decision, comment: comment.trim() });
      toast.success(decision === 'APPROVED' ? `Claim approved. Settlement is starting.` : 'Claim rejected.');
      onClaimChanged();
      if (decision === 'APPROVED') goTo('settlement');
    } catch (err) {
      setError(err.message);
      toast.error(err.message);
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  };

  if (!editable) {
    return (
      <div className="stack">
        <LockedNotice claim={claim} what="Decisions" />
        {claim.status === 'REJECTED' && <Card title="Decision"><p><strong>Rejected.</strong> {claim.rejectionReason}</p></Card>}
        {claim.approvedAmount != null && <Card title="Decision"><p><strong>Approved</strong> for <span className="num">{money(claim.approvedAmount)}</span>. <button type="button" className="small" style={{ all: 'unset', color: 'var(--accent)', cursor: 'pointer', textDecoration: 'underline' }} onClick={() => goTo('settlement')}>View settlement</button></p></Card>}
      </div>
    );
  }

  return (
    <Card title="Record a decision">
      <div className="stack" style={{ maxWidth: 640 }}>
        {claim.eligibility === 'INELIGIBLE' && (
          <div className="notice warning">This claim failed eligibility. Approving it goes against the rule result. Explain why in your comment, or override eligibility first.</div>
        )}
        <div className={s.choice} role="group" aria-label="Decision">
          <button type="button" aria-pressed={decision === 'APPROVED'} className={`${s.choiceBtn} ${s.approve}`} onClick={() => { setDecision('APPROVED'); setConfirming(false); }} disabled={busy}>
            <span className={s.choiceTitle}>✓ Approve</span>
            <span className="small secondary">Pay {money(claim.proposedAmount)} and start settlement</span>
          </button>
          <button type="button" aria-pressed={decision === 'REJECTED'} className={`${s.choiceBtn} ${s.reject}`} onClick={() => { setDecision('REJECTED'); setConfirming(false); }} disabled={busy}>
            <span className={s.choiceTitle}>✕ Reject</span>
            <span className="small secondary">The policyholder sees your comment as the reason</span>
          </button>
        </div>
        <CommentField
          value={comment}
          onChange={(v) => { setComment(v); setConfirming(false); }}
          label={decision === 'REJECTED' ? 'Rejection reason (shown to the policyholder)' : 'Justification comment'}
        />
        {error && <div className="notice danger" role="alert">{error}</div>}

        {!confirming ? (
          <div><Button onClick={() => setConfirming(true)} disabled={!canContinue}>Continue</Button></div>
        ) : (
          <div className={s.confirm} role="alertdialog" aria-labelledby="confirm-title" aria-describedby="confirm-desc">
            <strong id="confirm-title">
              {decision === 'APPROVED' ? `Approve ${claim.id} for ${money(claim.proposedAmount)}?` : `Reject ${claim.id}?`}
            </strong>
            <p id="confirm-desc" className="small secondary">
              {decision === 'APPROVED'
                ? 'This starts a three-phase commit settlement with Claim Intake, Policy and Payment. It cannot be undone from here.'
                : 'The claim will be closed and the policyholder notified. This cannot be undone from here.'}
            </p>
            <div className="row">
              <Button variant={decision === 'APPROVED' ? 'primary' : 'danger'} onClick={submit} loading={busy}>
                {decision === 'APPROVED' ? 'Yes, approve' : 'Yes, reject'}
              </Button>
              <Button variant="secondary" onClick={() => setConfirming(false)} disabled={busy}>Go back</Button>
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}
