import { useState } from 'react';
import { Link } from 'react-router-dom';
import { opsApi } from '../../api/services.js';
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
  },
];

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
      toast.error(err.message);
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
              return (
                <Card key={t.target} title={t.title} actions={on ? <Badge tone="danger" glyph="⚡">On</Badge> : <Badge tone="neutral" glyph="○">Off</Badge>}>
                  <div className="stack">
                    <p className="small secondary">{t.desc}</p>
                    <div className="row">
                      <button
                        type="button"
                        role="switch"
                        aria-checked={on}
                        aria-label={t.title}
                        className={s.switch}
                        disabled={busy === t.target}
                        onClick={() => toggle(t, !on)}
                      />
                      <span className="small strong">{on ? `Mode: ${t.onMode}` : 'Normal operation'}</span>
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
