import { useState } from 'react';
import { assessmentApi } from '../../../api/services.js';
import { useApi } from '../../../lib/hooks.js';
import { money, dateTime } from '../../../lib/format.js';
import { useToast } from '../../../components/Toast.jsx';
import Card from '../../../components/Card.jsx';
import Button from '../../../components/Button.jsx';
import AsyncState from '../../../components/AsyncState.jsx';
import EmptyState from '../../../components/EmptyState.jsx';
import CommentModal from './CommentModal.jsx';
import LockedNotice, { isEditable } from './LockedNotice.jsx';
import s from './review.module.css';

export default function AssessmentTab({ claim, onClaimChanged }) {
  const toast = useToast();
  const a = useApi(() => assessmentApi.get(claim.id), [claim.id, claim.updatedAt]);
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState('');

  const num = Number(amount);
  const amountError = amount === '' ? null
    : !Number.isFinite(num) || num < 0 ? 'Enter a valid amount.'
      : num > claim.claimedAmount ? `Cannot exceed the claimed ${money(claim.claimedAmount)}.` : null;

  const submit = async (comment) => {
    await assessmentApi.adjust(claim.id, { amount: num, comment });
    toast.success(`Proposed amount set to ${money(num)}.`);
    a.reload({ silent: true });
    onClaimChanged();
  };

  if (a.error?.status === 404) {
    return <div className="stack"><LockedNotice claim={claim} what="Adjustments" /><Card><EmptyState title="Not assessed yet" message="The Assessment service calculates the amount after eligibility is checked." /></Card></div>;
  }

  return (
    <div className="stack">
      <LockedNotice claim={claim} what="Adjustments" />
      <AsyncState {...a} onRetry={a.reload}>
        {(x) => (
          <div className={s.split}>
            <Card
              title="Calculation"
              actions={<Button variant="secondary" size="sm" disabled={!isEditable(claim)} onClick={() => { setAmount(String(x.proposedAmount)); setOpen(true); }}>Adjust amount</Button>}
            >
              <table className={s.calc}>
                <caption className="sr-only">Assessment calculation breakdown</caption>
                <tbody>
                  <tr><td className={s.op} /><td>Claimed amount</td><td>{money(x.claimedAmount)}</td></tr>
                  <tr><td className={s.op}>↓</td><td>Capped at sub-limit<span className={s.note}>{claim.treatmentType} sub-limit {money(x.subLimit)}</span></td><td>{money(x.cappedAmount)}</td></tr>
                  <tr><td className={s.op}>−</td><td>Deductible</td><td>{money(x.deductible)}</td></tr>
                  <tr><td className={s.op} /><td>After deductible</td><td>{money(x.afterDeductible)}</td></tr>
                  <tr><td className={s.op}>−</td><td>Co-pay ({x.coPayPercent}%)</td><td>{money(x.coPayAmount)}</td></tr>
                  <tr><td className={s.op}>=</td><td>Calculated amount</td><td>{money(x.calculatedAmount)}</td></tr>
                  <tr className={s.total}><td className={s.op} /><td>Proposed amount{x.adjustments.length > 0 && <span className={s.note}>adjusted by an adjuster</span>}</td><td>{money(x.proposedAmount)}</td></tr>
                </tbody>
              </table>
              <p className="xsmall muted" style={{ marginTop: 12 }}>Assessed {dateTime(x.assessedAt)}</p>
            </Card>
            <Card title="Adjustments">
              {x.adjustments.length === 0 ? <EmptyState title="No manual adjustments" /> : (
                <ul className="stack" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {x.adjustments.slice().reverse().map((adj) => (
                    <li key={adj.at} className="stack-sm">
                      <div className="row-between small"><span className="strong num">{money(adj.from)} → {money(adj.to)}</span><span className="muted xsmall">{dateTime(adj.at)}</span></div>
                      <div className="small secondary">{adj.by.name}: “{adj.comment}”</div>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>
        )}
      </AsyncState>

      <CommentModal
        open={open}
        title="Adjust proposed amount"
        confirmLabel="Save amount"
        onClose={() => setOpen(false)}
        onSubmit={submit}
        valid={amount !== '' && !amountError}
      >
        <div className="field">
          <label htmlFor="adj-amount">New proposed amount (₹)</label>
          <input id="adj-amount" className="input num" inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d]/g, ''))} aria-invalid={Boolean(amountError)} data-autofocus />
          {amountError ? <span className="error">{amountError}</span> : <span className="hint">{amount ? money(num) : ''} · claimed {money(claim.claimedAmount)}</span>}
        </div>
      </CommentModal>
    </div>
  );
}
