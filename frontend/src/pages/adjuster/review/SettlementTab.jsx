import { useState } from 'react';
import { approvalApi } from '../../../api/services.js';
import { isServiceUnavailable } from '../../../api/client.js';
import { POLL_INTERVAL_MS, LIVE_POLL_INTERVAL_MS } from '../../../config/api.js';
import { useApi, useInterval } from '../../../lib/hooks.js';
import { money, dateTime, time } from '../../../lib/format.js';
import { useToast } from '../../../components/Toast.jsx';
import Card from '../../../components/Card.jsx';
import Table from '../../../components/Table.jsx';
import Badge from '../../../components/Badge.jsx';
import Button from '../../../components/Button.jsx';
import AsyncState from '../../../components/AsyncState.jsx';
import EmptyState from '../../../components/EmptyState.jsx';
import s from './review.module.css';

const PHASES = [
  { id: 'CAN_COMMIT', label: 'CanCommit', desc: 'Collect votes' },
  { id: 'PRE_COMMIT', label: 'PreCommit', desc: 'Prepare and acknowledge' },
  { id: 'DO_COMMIT', label: 'DoCommit', desc: 'Commit and acknowledge' },
];
const ORDER = { CAN_COMMIT: 0, PRE_COMMIT: 1, DO_COMMIT: 2, DONE: 3 };
const PARTICIPANT_LABEL = { APPROVAL: 'Approval (coordinator)', CLAIM_INTAKE: 'Claim Intake', POLICY: 'Policy', PAYMENT: 'Payment' };

function phaseState(tx, i) {
  const cur = ORDER[tx.phase];
  if (tx.outcome === 'ABORTED') return i < cur ? 'done' : i === cur ? 'failed' : 'pending';
  if (tx.outcome === 'COMMITTED') return 'done';
  return i < cur ? 'done' : i === cur ? 'active' : 'pending';
}
const PHASE_TEXT = { done: 'Complete', active: 'In progress', failed: 'Aborted', pending: 'Waiting' };

/**
 * Shown while the Approval service (the 3PC coordinator) cannot be reached. The usual cause is the
 * "Crash coordinator after PreCommit" chaos mode: the coordinator really stops, and Kubernetes restarts it.
 */
function CoordinatorRestarting({ stale }) {
  return (
    <div className="notice warning" role="status" aria-live="polite">
      <strong>↻ The settlement coordinator is restarting.</strong>{' '}
      The Approval service stopped. Usually this is the &ldquo;Crash coordinator after PreCommit&rdquo; chaos mode.
      The participants keep their PreCommit. When the coordinator is back, typically within 1 to 2 minutes
      (longer if it crashed several times in a row), it recovers this settlement from its transaction log and
      finishes DoCommit. This page keeps checking and updates on its own.
      {stale && ' The details below are from before it stopped.'}
    </div>
  );
}

function Vote({ v }) {
  if (v === 'YES') return <Badge tone="success" glyph="✓">YES</Badge>;
  if (v === 'NO') return <Badge tone="danger" glyph="✕">NO</Badge>;
  return <Badge tone="neutral" glyph="…">Waiting</Badge>;
}
function Ack({ ok, aborted, reached }) {
  if (aborted) return <Badge tone="danger" glyph="✕">Aborted</Badge>;
  if (ok) return <Badge tone="success" glyph="✓">ACK</Badge>;
  if (!reached) return <span className="muted xsmall">—</span>;
  return <Badge tone="neutral" glyph="…">Waiting</Badge>;
}

export default function SettlementTab({ claim, onClaimChanged }) {
  const toast = useToast();
  const tx = useApi(() => approvalApi.settlement(claim.id), [claim.id]);
  const [retrying, setRetrying] = useState(false);

  // The coordinator is unreachable: the first load failed with 502/503/504, or a background refresh did.
  const coordinatorDown = isServiceUnavailable(tx.error) || isServiceUnavailable(tx.pollError);

  const inFlight = tx.data?.outcome === 'IN_PROGRESS' || (['APPROVED', 'SETTLING'].includes(claim.status) && !tx.data);
  // Keep polling while the coordinator is down, so the page recovers as soon as it is back.
  const watch = inFlight || coordinatorDown || ['APPROVED', 'SETTLING'].includes(claim.status);
  // The review page polls the claim itself; here we only refresh the transaction.
  useInterval(() => tx.reload({ silent: true }), inFlight || coordinatorDown ? LIVE_POLL_INTERVAL_MS : POLL_INTERVAL_MS, watch);

  const retry = async () => {
    setRetrying(true);
    try {
      await approvalApi.retry(claim.id);
      toast.success('Settlement retry started.');
      await tx.reload({ silent: true });
      onClaimChanged();
    } catch (err) {
      toast.error(err.message);
    } finally {
      setRetrying(false);
    }
  };

  const canRetry = !coordinatorDown && (claim.status === 'SETTLEMENT_FAILED' || (claim.status === 'APPROVED' && !tx.data));

  // Nothing loaded yet and the coordinator is down: explain instead of showing a generic error.
  if (coordinatorDown && !tx.data) {
    return (
      <Card>
        <CoordinatorRestarting stale={false} />
      </Card>
    );
  }

  return (
    <AsyncState
      {...tx}
      onRetry={tx.reload}
      empty={(
        <Card>
          <EmptyState
            title={claim.status === 'APPROVED' ? 'Settlement not started' : 'No settlement yet'}
            message={claim.status === 'APPROVED' ? 'The claim is approved and waiting for the coordinator to start settlement.' : 'Settlement starts automatically when the claim is approved.'}
            action={canRetry && <Button onClick={retry} loading={retrying}>Start settlement</Button>}
          />
        </Card>
      )}
    >
      {(t) => (
        <div className="stack">
          {coordinatorDown && <CoordinatorRestarting stale />}
          <Card
            title={<span>Transaction <span className="mono">{t.transactionId}</span>{t.attempt > 1 && <span className="muted small"> · attempt {t.attempt}</span>}</span>}
            actions={(
              <>
                {t.outcome === 'COMMITTED' && <Badge tone="success" glyph="✓">Committed</Badge>}
                {t.outcome === 'ABORTED' && <Badge tone="danger" glyph="✕">Aborted</Badge>}
                {t.outcome === 'IN_PROGRESS' && <Badge tone="warning" glyph="⇄">In progress</Badge>}
                {canRetry && <Button size="sm" onClick={retry} loading={retrying}>Retry settlement</Button>}
              </>
            )}
          >
            <div className="stack">
              <ol className={s.phases} aria-label="Three-phase commit progress">
                {PHASES.map((p, i) => {
                  const st = phaseState(t, i);
                  return (
                    <li key={p.id} className={`${s.phase} ${s[st]}`} aria-current={st === 'active' ? 'step' : undefined}>
                      <span className={s.phaseDot} aria-hidden="true">{st === 'done' ? '✓' : st === 'failed' ? '✕' : i + 1}</span>
                      <span className={s.phaseName}>{p.label}</span>
                      <span className={s.phaseState}>{PHASE_TEXT[st]} · {p.desc}</span>
                    </li>
                  );
                })}
              </ol>
              <div className="row small" style={{ justifyContent: 'center', gap: 24 }}>
                <span>Amount <strong className="num">{money(t.amount)}</strong></span>
                <span>Coordinator {coordinatorDown ? <Badge tone="warning" glyph="↻">Restarting</Badge> : t.coordinator === 'UP' ? <Badge tone="success" glyph="●">Up</Badge> : t.coordinator === 'DOWN' ? <Badge tone="danger" glyph="✕">Down</Badge> : <Badge tone="warning" glyph="↻">Recovering</Badge>}</span>
                <span className="muted">Started {dateTime(t.startedAt)}</span>
                {t.finishedAt && <span className="muted">Finished {dateTime(t.finishedAt)}</span>}
              </div>
              {t.abortReason && <div className="notice danger"><strong>Outcome: aborted.</strong> {t.abortReason} No participant committed, so no money moved.</div>}
              {t.outcome === 'COMMITTED' && <div className="notice success"><strong>Outcome: committed.</strong> All participants committed. Payment reference <span className="mono">{claim.paymentReference}</span>.</div>}
              {t.coordinator === 'DOWN' && <div className="notice warning">The coordinator is down. Participants are in PreCommit and will commit when it recovers from its transaction log.</div>}
            </div>
          </Card>

          <div className={s.split}>
            <Card title="Participants" padded={false}>
              <Table
                rowKey="name"
                caption="3PC participants"
                rows={t.participants}
                columns={[
                  { key: 'name', header: 'Participant', render: (p) => <span className="strong">{PARTICIPANT_LABEL[p.name] || p.name}</span> },
                  { key: 'vote', header: 'CanCommit vote', render: (p) => <Vote v={p.vote} /> },
                  { key: 'pre', header: 'PreCommit', render: (p) => <Ack ok={p.preCommitAck} aborted={p.aborted && !p.preCommitAck} reached={ORDER[t.phase] >= 1 || t.outcome === 'ABORTED'} /> },
                  { key: 'do', header: 'DoCommit', render: (p) => <Ack ok={p.doCommitAck} aborted={false} reached={ORDER[t.phase] >= 2} /> },
                ]}
              />
            </Card>
            <Card title="Coordinator log">
              <ol className={s.log} aria-label="Coordinator log">
                {t.log.slice().reverse().map((l, i) => (
                  <li key={`${l.at}-${i}`} className={s[l.level]}>
                    <time>{time(l.at)}</time>
                    <span>{l.level === 'error' ? '✕ ' : l.level === 'warn' ? '! ' : ''}{l.message}</span>
                  </li>
                ))}
              </ol>
            </Card>
          </div>
        </div>
      )}
    </AsyncState>
  );
}
