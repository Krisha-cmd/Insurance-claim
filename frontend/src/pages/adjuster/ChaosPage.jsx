import { useState } from 'react';
import { Link } from 'react-router-dom';
import { opsApi } from '../../api/services.js';
import { isServiceUnavailable } from '../../api/client.js';
import { POLL_INTERVAL_MS } from '../../config/api.js';
import { useApi, useInterval } from '../../lib/hooks.js';
import { dateTime } from '../../lib/format.js';
import { useToast } from '../../components/Toast.jsx';
import Card from '../../components/Card.jsx';
import Badge from '../../components/Badge.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import s from './adjuster.module.css';

const TOGGLES = [
  {
    target: 'PAYMENT', onMode: 'FAIL', title: 'Fail payment gateway',
    desc: 'The Payment participant votes NO in CanCommit, so the coordinator aborts the transaction and the claim goes to Settlement failed.',
  },
  {
    target: 'COORDINATOR', onMode: 'CRASH_AFTER_PRECOMMIT', title: 'Crash coordinator after PreCommit',
    desc: 'The Approval coordinator crashes once every participant has acknowledged PreCommit. It then recovers from its transaction log and finishes DoCommit.',
    note: 'This is a real crash: the Approval service stops and Kubernetes restarts it. Approvals and the Settlement tab are unavailable for about 1 to 2 minutes, and longer when it crashes several times in a row (Kubernetes waits longer before each restart). One crash switches this back to Off.',
  },
];

/** Which service hosts each target, for messages. */
const SERVICE_OF = { PAYMENT: 'Payment', COORDINATOR: 'Approval' };

/** ops-dashboard answers 502 when it cannot reach the target service, which is normal while it restarts. */
function toggleErrorMessage(t, err) {
  if (isServiceUnavailable(err)) {
    return `${SERVICE_OF[t.target]} is not reachable right now. It is probably restarting after a chaos crash. Try again in a minute.`;
  }
  return err.message;
}

export default function ChaosPage() {
  const toast = useToast();
  const chaos = useApi(() => opsApi.chaos(), []);
  const [busy, setBusy] = useState(null);
  useInterval(() => chaos.reload({ silent: true }), POLL_INTERVAL_MS);

  const toggle = async (t, on) => {
    setBusy(t.target);
    try {
      const next = await opsApi.setChaos(t.target, on ? t.onMode : 'OFF');
      chaos.setData(next);
      toast.success(`${t.title}: ${on ? 'on' : 'off'}.`);
    } catch (err) {
      toast.error(toggleErrorMessage(t, err));
      chaos.reload({ silent: true }); // refresh, so the card shows the target as unreachable
    } finally {
      setBusy(null);
    }
  };

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Chaos controls</h1>
          <p>Demo only. Inject failures into the 3PC settlement and watch the outcome live.</p>
        </div>
      </div>
      <div className="notice warning" style={{ marginBottom: 16 }}>
        These toggles affect <strong>every</strong> settlement that starts while they are on. Approve a claim, or retry a failed settlement, to trigger them.
      </div>
      <AsyncState {...chaos} onRetry={chaos.reload} isEmpty={() => false}>
        {(state) => (
          <div className={s.chaosGrid}>
            {TOGGLES.map((t) => {
              const entry = state[t.target] || {};
              const on = entry.mode === t.onMode;
              // ops-dashboard reports reachable=false when it cannot ask the service (it is down or restarting).
              const unreachable = entry.reachable === false;
              const badge = unreachable
                ? <Badge tone="warning" glyph="↻">Restarting</Badge>
                : on ? <Badge tone="danger" glyph="⚡">On</Badge> : <Badge tone="neutral" glyph="○">Off</Badge>;
              return (
                <Card key={t.target} title={t.title} actions={badge}>
                  <div className="stack">
                    <p className="small secondary">{t.desc}</p>
                    {t.note && <p className="xsmall muted">{t.note}</p>}
                    {unreachable && (
                      <div className="notice warning small" role="status">
                        {SERVICE_OF[t.target]} is not reachable, probably restarting after a chaos crash. The switch
                        works again once it is back. This page checks every few seconds.
                      </div>
                    )}
                    <div className="row">
                      <button
                        type="button"
                        role="switch"
                        aria-checked={on}
                        aria-label={t.title}
                        className={s.switch}
                        disabled={busy === t.target || unreachable}
                        onClick={() => toggle(t, !on)}
                      />
                      <span className="small strong">{unreachable ? 'Unavailable while restarting' : on ? `Mode: ${t.onMode}` : 'Normal operation'}</span>
                    </div>
                    <dl className="dl small">
                      <dt>Last changed</dt>
                      <dd>{entry.changedAt ? `${dateTime(entry.changedAt)} by ${entry.changedBy?.name ?? 'unknown'}` : 'Never'}</dd>
                      <dt>Affected claim</dt>
                      <dd>
                        {entry.affectedClaimId
                          ? <Link to={`/ops/claims/${entry.affectedClaimId}?tab=settlement`} className="mono">{entry.affectedClaimId} → Settlement tab</Link>
                          : <span className="muted">{on ? 'Waiting for the next settlement…' : 'None'}</span>}
                      </dd>
                    </dl>
                  </div>
                </Card>
              );
            })}
          </div>
        )}
      </AsyncState>
    </div>
  );
}
