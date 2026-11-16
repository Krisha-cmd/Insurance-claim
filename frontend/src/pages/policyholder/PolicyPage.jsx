import { policyApi } from '../../api/services.js';
import { useApi } from '../../lib/hooks.js';
import { money, date } from '../../lib/format.js';
import Card from '../../components/Card.jsx';
import Table from '../../components/Table.jsx';
import Badge from '../../components/Badge.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import PolicySummaryCard from './parts/PolicySummaryCard.jsx';

export default function PolicyPage() {
  const data = useApi(async () => {
    const policy = await policyApi.mine();
    const coverage = await policyApi.coverage(policy.policyNumber);
    return { policy, coverage };
  }, []);

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>My policy</h1>
          <p>What your plan covers, the limits, and who is insured.</p>
        </div>
      </div>
      <AsyncState {...data} onRetry={data.reload}>
        {({ policy, coverage }) => (
          <div className="stack-lg">
            <PolicySummaryCard policy={policy} showLink={false} />

            <div className="grid-2">
              <Card title="Cost sharing">
                <dl className="dl">
                  <dt>Deductible</dt><dd className="num">{money(coverage.deductible)} per claim</dd>
                  <dt>Co-pay</dt><dd>{coverage.coPayPercent}% of the amount after deductible</dd>
                  <dt>Validity</dt><dd>{date(coverage.validFrom)} – {date(coverage.validTo)}</dd>
                </dl>
              </Card>
              <Card title="Insured members">
                <ul className="stack-sm" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {policy.members.map((m) => (
                    <li key={m.id} className="row-between">
                      <span className="strong">{m.name}</span>
                      <span className="muted small">{m.relation} · born {date(m.dob)}</span>
                    </li>
                  ))}
                </ul>
              </Card>
            </div>

            <Card title="Coverage" padded={false}>
              <Table
                caption="Covered treatments"
                rowKey="treatment"
                columns={[
                  { key: 'treatment', header: 'Treatment', render: (r) => <span className="strong">{r.treatment}</span> },
                  { key: 'covered', header: 'Covered', render: (r) => (r.covered ? <Badge tone="success" glyph="✓">Covered</Badge> : <Badge tone="danger" glyph="✕">Not covered</Badge>) },
                  { key: 'subLimit', header: 'Sub-limit', align: 'right', render: (r) => <span className="num">{r.covered ? money(r.subLimit) : '—'}</span> },
                  { key: 'waitingPeriodDays', header: 'Waiting period', render: (r) => (r.covered ? (r.waitingPeriodDays ? `${r.waitingPeriodDays} days` : 'None') : '—') },
                  { key: 'notes', header: 'Notes', render: (r) => <span className="muted">{r.notes || '—'}</span> },
                ]}
                rows={coverage.treatments}
              />
            </Card>

            <Card title="Exclusions">
              <p className="small secondary" style={{ marginBottom: 12 }}>These are never covered by your plan.</p>
              <ul style={{ margin: 0, paddingLeft: 20 }} className="stack-sm">
                {coverage.exclusions.map((e) => <li key={e.keyword}>{e.label}</li>)}
              </ul>
            </Card>
          </div>
        )}
      </AsyncState>
    </div>
  );
}
