import { useState } from 'react';
import { eligibilityApi } from '../../../api/services.js';
import { useApi } from '../../../lib/hooks.js';
import { dateTime } from '../../../lib/format.js';
import { useToast } from '../../../components/Toast.jsx';
import Card from '../../../components/Card.jsx';
import Table from '../../../components/Table.jsx';
import Badge from '../../../components/Badge.jsx';
import Button from '../../../components/Button.jsx';
import AsyncState from '../../../components/AsyncState.jsx';
import EmptyState from '../../../components/EmptyState.jsx';
import CommentModal from './CommentModal.jsx';
import LockedNotice, { isEditable } from './LockedNotice.jsx';

const REASONS = [
  'Waiting period waived (portability)',
  'Medical necessity confirmed',
  'Rule misapplied',
  'Documentation clarifies coverage',
  'Policy terms exclude treatment',
  'Other',
];

export default function EligibilityTab({ claim, onClaimChanged }) {
  const toast = useToast();
  const el = useApi(() => eligibilityApi.get(claim.id), [claim.id, claim.updatedAt]);
  const [open, setOpen] = useState(false);
  const [eligible, setEligible] = useState(true);
  const [reason, setReason] = useState('');

  const openModal = () => { setEligible(!el.data.eligible); setReason(''); setOpen(true); };

  const submit = async (comment) => {
    await eligibilityApi.override(claim.id, { eligible, reason, comment });
    toast.success(`Eligibility overridden to ${eligible ? 'eligible' : 'not eligible'}.`);
    el.reload({ silent: true });
    onClaimChanged();
  };

  const notChecked = el.error?.status === 404;

  return (
    <div className="stack">
      <LockedNotice claim={claim} what="Overrides" />
      {notChecked ? <Card><EmptyState title="Eligibility not checked yet" message="This runs automatically once all required documents are verified." /></Card> : (
        <AsyncState {...el} onRetry={el.reload}>
          {(e) => (
            <>
              <Card
                title="Result"
                actions={<Button variant="secondary" size="sm" onClick={openModal} disabled={!isEditable(claim)}>Override</Button>}
              >
                <div className="row" style={{ gap: 16 }}>
                  {e.eligible ? <Badge tone="success" glyph="✓">Eligible</Badge> : <Badge tone="danger" glyph="✕">Not eligible</Badge>}
                  <span className="small muted">Evaluated {dateTime(e.evaluatedAt)}</span>
                  {e.overridden && <Badge tone="warning" glyph="✎">Overridden</Badge>}
                </div>
                {e.override && (
                  <div className="notice warning" style={{ marginTop: 16 }}>
                    <strong>{e.override.by.name}</strong> overrode this to <strong>{e.override.eligible ? 'eligible' : 'not eligible'}</strong> on {dateTime(e.override.at)}.
                    <div>Reason: {e.override.reason}</div>
                    <div>“{e.override.comment}”</div>
                  </div>
                )}
              </Card>
              <Card title="Rule checks" padded={false}>
                <Table
                  rowKey="rule"
                  caption="Eligibility rule checks"
                  rows={e.checks}
                  columns={[
                    { key: 'passed', header: 'Result', width: 110, render: (r) => (r.passed ? <Badge tone="success" glyph="✓">Pass</Badge> : <Badge tone="danger" glyph="✕">Fail</Badge>) },
                    { key: 'label', header: 'Rule', render: (r) => <span><span className="strong">{r.label}</span> <span className="xsmall muted mono">{r.rule}</span></span> },
                    { key: 'detail', header: 'Detail', render: (r) => <span className="secondary">{r.detail}</span> },
                  ]}
                />
              </Card>
            </>
          )}
        </AsyncState>
      )}

      <CommentModal
        open={open}
        title="Override eligibility"
        confirmLabel="Save override"
        onClose={() => setOpen(false)}
        onSubmit={submit}
        valid={Boolean(reason)}
      >
        <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className="label" style={{ marginBottom: 4 }}>New result</legend>
          <div className="row">
            <label className="row" style={{ gap: 6 }}><input type="radio" name="elig" checked={eligible} onChange={() => setEligible(true)} /> Eligible</label>
            <label className="row" style={{ gap: 6 }}><input type="radio" name="elig" checked={!eligible} onChange={() => setEligible(false)} /> Not eligible</label>
          </div>
        </fieldset>
        <div className="field">
          <label htmlFor="override-reason">Reason</label>
          <select id="override-reason" className="select" value={reason} onChange={(ev) => setReason(ev.target.value)}>
            <option value="">Choose a reason…</option>
            {REASONS.map((r) => <option key={r}>{r}</option>)}
          </select>
        </div>
      </CommentModal>
    </div>
  );
}
