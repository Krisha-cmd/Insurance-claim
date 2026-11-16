import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { claimsApi } from '../../api/services.js';
import { useApi } from '../../lib/hooks.js';
import { STATUS_LIST, STATUS_META } from '../../lib/status.js';
import Card from '../../components/Card.jsx';
import { ButtonLink } from '../../components/Button.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ClaimList from './parts/ClaimList.jsx';

export default function MyClaimsPage() {
  const claims = useApi(() => claimsApi.mine(), []);
  const [params, setParams] = useSearchParams();
  const status = params.get('status') || '';

  const filtered = useMemo(() => (claims.data || []).filter((c) => !status || c.status === status), [claims.data, status]);
  const present = useMemo(() => new Set((claims.data || []).map((c) => c.status)), [claims.data]);

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>My claims</h1>
          <p>Every claim you’ve filed and where it stands.</p>
        </div>
        <ButtonLink to="/portal/claims/new">+ File a claim</ButtonLink>
      </div>
      <Card
        padded={false}
        title="Claims"
        actions={(
          <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <label htmlFor="status-filter">Status</label>
            <select
              id="status-filter" className="select" style={{ minHeight: 34, width: 'auto' }}
              value={status}
              onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}
            >
              <option value="">All</option>
              {STATUS_LIST.filter((st) => present.has(st)).map((st) => <option key={st} value={st}>{STATUS_META[st].label}</option>)}
            </select>
          </div>
        )}
      >
        <AsyncState
          {...claims}
          data={claims.data && filtered}
          onRetry={claims.reload}
          empty={status
            ? <EmptyState title="No claims with this status" action={<button type="button" className="small" style={{ all: 'unset', color: 'var(--accent)', cursor: 'pointer' }} onClick={() => setParams({})}>Clear filter</button>} />
            : <EmptyState title="No claims yet" message="File your first claim to see it here." action={<ButtonLink to="/portal/claims/new">File a claim</ButtonLink>} />}
        >
          {(list) => <ClaimList claims={list} />}
        </AsyncState>
      </Card>
    </div>
  );
}
