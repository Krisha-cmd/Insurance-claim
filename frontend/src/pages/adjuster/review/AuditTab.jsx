import { auditApi } from '../../../api/services.js';
import { useApi } from '../../../lib/hooks.js';
import { dateTime } from '../../../lib/format.js';
import { describeEvent } from '../../../lib/auditText.js';
import Card from '../../../components/Card.jsx';
import Table from '../../../components/Table.jsx';
import AsyncState from '../../../components/AsyncState.jsx';
import EmptyState from '../../../components/EmptyState.jsx';
import s from './review.module.css';

const fmt = (o) => (o ? Object.entries(o).map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') || '—' : v ?? '—'}`).join('\n') : null);

export function ActorCell({ actor }) {
  return (
    <span>
      <span className="strong">{actor.name}</span>
      <div className="xsmall muted">{actor.role === 'SYSTEM' ? 'System' : actor.role === 'ADJUSTER' ? 'Adjuster' : 'Policyholder'}</div>
    </span>
  );
}

export default function AuditTab({ claim }) {
  const trail = useApi(() => auditApi.trail(claim.id), [claim.id, claim.updatedAt]);
  return (
    <Card padded={false} title="Audit trail" actions={<span className="xsmall muted">Every event for this claim, newest first</span>}>
      <AsyncState {...trail} onRetry={trail.reload} empty={<EmptyState title="No events recorded" />}>
        {(events) => (
          <Table
            dense
            caption="Audit events"
            rows={events.slice().reverse()}
            columns={[
              { key: 'at', header: 'Time', width: 150, render: (e) => <span className="num">{dateTime(e.at)}</span> },
              {
                key: 'type', header: 'Event',
                render: (e) => {
                  const d = describeEvent(e);
                  return <div><div className="strong">{d.title}</div><div className="xsmall muted mono">{e.type}</div></div>;
                },
              },
              { key: 'actor', header: 'Actor', render: (e) => <ActorCell actor={e.actor} /> },
              {
                key: 'change', header: 'Before → after',
                render: (e) => (e.before || e.after ? (
                  <div>
                    {e.before && <pre className={`${s.diff} ${s.before}`}>− {fmt(e.before)}</pre>}
                    {e.after && <pre className={`${s.diff} ${s.after}`}>+ {fmt(e.after)}</pre>}
                  </div>
                ) : <span className="muted">—</span>),
              },
              {
                key: 'comment', header: 'Justification',
                render: (e) => (e.comment ? <span className="small">“{e.comment}”</span> : <span className="muted">—</span>),
              },
            ]}
          />
        )}
      </AsyncState>
    </Card>
  );
}
