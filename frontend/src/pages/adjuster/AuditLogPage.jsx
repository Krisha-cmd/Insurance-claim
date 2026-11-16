import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { auditApi } from '../../api/services.js';
import { useApi } from '../../lib/hooks.js';
import { dateTime, humanize } from '../../lib/format.js';
import { AUDIT_TYPES, describeEvent } from '../../lib/auditText.js';
import Card from '../../components/Card.jsx';
import Table from '../../components/Table.jsx';
import Button from '../../components/Button.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import { ActorCell } from './review/AuditTab.jsx';
import s from './adjuster.module.css';

export default function AuditLogPage() {
  const [type, setType] = useState('');
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  useEffect(() => { const t = setTimeout(() => setDebounced(q), 300); return () => clearTimeout(t); }, [q]);

  const events = useApi(() => auditApi.search({ type, q: debounced, from, to }), [type, debounced, from, to]);
  const filtered = Boolean(type || debounced || from || to);

  return (
    <div>
      <div className="page-header"><div><h1>Audit log</h1><p>Every recorded event across all claims, newest first.</p></div></div>
      <Card padded={false}>
        <div className={s.filters}>
          <div className="field" style={{ flex: 2, minWidth: 220 }}>
            <label htmlFor="al-q">Search</label>
            <input id="al-q" className="input" type="search" placeholder="Claim ID or actor name" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="al-type">Event type</label>
            <select id="al-type" className="select" value={type} onChange={(e) => setType(e.target.value)}>
              <option value="">Any</option>
              {AUDIT_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="al-from">From</label>
            <input id="al-from" className="input" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="al-to">To</label>
            <input id="al-to" className="input" type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </div>
          {filtered && <Button variant="ghost" size="sm" onClick={() => { setType(''); setQ(''); setFrom(''); setTo(''); }}>Clear</Button>}
        </div>
        <AsyncState {...events} onRetry={events.reload} empty={<EmptyState title="No matching events" />}>
          {(d) => (
            <>
              <Table
                dense
                caption="Audit events"
                rows={d.items}
                defaultSort={{ key: 'at', dir: 'desc' }}
                columns={[
                  { key: 'at', header: 'Time', sortable: true, width: 160, render: (e) => <span className="num">{dateTime(e.at)}</span> },
                  { key: 'type', header: 'Type', sortable: true, render: (e) => <div><div className="strong">{describeEvent(e).title}</div><div className="xsmall muted mono">{e.type}</div></div> },
                  { key: 'claimId', header: 'Claim', sortable: true, render: (e) => (e.claimId ? <Link to={`/ops/claims/${e.claimId}?tab=audit`} className="mono">{e.claimId}</Link> : <span className="muted">—</span>) },
                  { key: 'actor', header: 'Actor', sortable: true, sortValue: (e) => e.actor.name, render: (e) => <ActorCell actor={e.actor} /> },
                  { key: 'comment', header: 'Comment', render: (e) => (e.comment ? <span className="small">“{e.comment}”</span> : <span className="muted">—</span>) },
                ]}
              />
              <div className={s.pager}><span className="small muted">Showing {d.items.length} of {d.total} events</span></div>
            </>
          )}
        </AsyncState>
      </Card>
    </div>
  );
}
