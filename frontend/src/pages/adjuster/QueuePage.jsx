import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { claimsApi } from '../../api/services.js';
import { POLL_INTERVAL_MS } from '../../config/api.js';
import { useApi, useInterval } from '../../lib/hooks.js';
import { money, age } from '../../lib/format.js';
import { STATUS_META, REVIEWABLE_STATUSES } from '../../lib/status.js';
import Card from '../../components/Card.jsx';
import Table from '../../components/Table.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import { EligibilityBadge } from './parts.jsx';

export const claimColumns = [
  { key: 'id', header: 'Claim', sortable: true, render: (c) => <span className="mono strong">{c.id}</span> },
  { key: 'policyholderName', header: 'Policyholder', sortable: true, render: (c) => <div><div>{c.policyholderName}</div><div className="xsmall muted mono">{c.policyNumber}</div></div> },
  { key: 'hospital', header: 'Hospital', sortable: true, render: (c) => <span className="small">{c.hospital}</span> },
  { key: 'claimedAmount', header: 'Claimed', sortable: true, align: 'right', render: (c) => <span className="num">{money(c.claimedAmount)}</span> },
  { key: 'proposedAmount', header: 'Proposed', sortable: true, align: 'right', render: (c) => <span className="num">{money(c.proposedAmount)}</span> },
  { key: 'eligibility', header: 'Eligibility', sortable: true, render: (c) => <EligibilityBadge value={c.eligibility} /> },
  { key: 'createdAt', header: 'Age', sortable: true, render: (c) => <span className="small" title={c.createdAt}>{age(c.createdAt)}</span> },
  { key: 'status', header: 'Status', sortable: true, sortValue: (c) => STATUS_META[c.status]?.label, render: (c) => <StatusBadge status={c.status} /> },
];

export default function QueuePage() {
  const navigate = useNavigate();
  const [status, setStatus] = useState('');
  const queue = useApi(() => claimsApi.queue({ status, page: 0, size: 100 }), [status]);
  useInterval(() => queue.reload({ silent: true }), POLL_INTERVAL_MS);

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Claims queue</h1>
          <p>Claims waiting for review, oldest first. Refreshes every {POLL_INTERVAL_MS / 1000} seconds.</p>
        </div>
      </div>
      <Card
        padded={false}
        title={`Waiting for review${queue.data ? ` (${queue.data.total})` : ''}`}
        actions={(
          <div className="row" style={{ gap: 8 }}>
            <label htmlFor="q-status" className="small secondary">Status</label>
            <select id="q-status" className="select" style={{ minHeight: 32, width: 'auto' }} value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">All waiting</option>
              {[...REVIEWABLE_STATUSES].map((st) => <option key={st} value={st}>{STATUS_META[st].label}</option>)}
            </select>
          </div>
        )}
      >
        <AsyncState {...queue} onRetry={queue.reload} empty={<EmptyState title="Queue is clear" message="No claims are waiting for review." />}>
          {(d) => (
            <Table
              caption="Claims waiting for review"
              columns={claimColumns}
              rows={d.items}
              defaultSort={{ key: 'createdAt', dir: 'asc' }}
              onRowClick={(c) => navigate(`/ops/claims/${c.id}`)}
            />
          )}
        </AsyncState>
      </Card>
    </div>
  );
}
