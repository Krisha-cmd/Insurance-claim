import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { claimsApi } from '../../api/services.js';
import { useApi } from '../../lib/hooks.js';
import { STATUS_LIST, STATUS_META } from '../../lib/status.js';
import Card from '../../components/Card.jsx';
import Table from '../../components/Table.jsx';
import Button from '../../components/Button.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import { claimColumns } from './QueuePage.jsx';
import s from './adjuster.module.css';

const PAGE_SIZE = 25;

export default function AllClaimsPage() {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);

  useEffect(() => { const t = setTimeout(() => setDebounced(q), 300); return () => clearTimeout(t); }, [q]);
  useEffect(() => { setPage(0); }, [debounced, status, from, to]);

  const claims = useApi(() => claimsApi.all({ q: debounced, status, from, to, page, size: PAGE_SIZE }), [debounced, status, from, to, page]);
  const total = claims.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const filtered = Boolean(debounced || status || from || to);

  return (
    <div>
      <div className="page-header"><div><h1>All claims</h1><p>Search every claim by ID or policy number.</p></div></div>
      <Card padded={false}>
        <div className={s.filters}>
          <div className="field" style={{ flex: 2, minWidth: 220 }}>
            <label htmlFor="ac-q">Search</label>
            <input id="ac-q" className="input" type="search" placeholder="Claim ID or policy number" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="ac-status">Status</label>
            <select id="ac-status" className="select" value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">Any</option>
              {STATUS_LIST.map((st) => <option key={st} value={st}>{STATUS_META[st].label}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="ac-from">Filed from</label>
            <input id="ac-from" className="input" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="ac-to">Filed to</label>
            <input id="ac-to" className="input" type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </div>
          {filtered && <Button variant="ghost" size="sm" onClick={() => { setQ(''); setStatus(''); setFrom(''); setTo(''); }}>Clear</Button>}
        </div>
        <AsyncState {...claims} onRetry={claims.reload} empty={<EmptyState title="No matching claims" message={filtered ? 'Try a different search or clear the filters.' : undefined} />}>
          {(d) => (
            <>
              <Table caption="All claims" columns={claimColumns} rows={d.items} onRowClick={(c) => navigate(`/ops/claims/${c.id}`)} />
              <div className={s.pager}>
                <span className="small muted">{total} claim{total === 1 ? '' : 's'} · page {page + 1} of {pages}</span>
                <div className="row" style={{ gap: 8 }}>
                  <Button variant="secondary" size="sm" disabled={page === 0 || claims.loading} onClick={() => setPage((p) => p - 1)}>Previous</Button>
                  <Button variant="secondary" size="sm" disabled={page + 1 >= pages || claims.loading} onClick={() => setPage((p) => p + 1)}>Next</Button>
                </div>
              </div>
            </>
          )}
        </AsyncState>
      </Card>
    </div>
  );
}
