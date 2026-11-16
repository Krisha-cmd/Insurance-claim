import { policyApi } from '../../../api/services.js';
import { useApi } from '../../../lib/hooks.js';
import { money, date, dateTime } from '../../../lib/format.js';
import Card from '../../../components/Card.jsx';
import Table from '../../../components/Table.jsx';
import Badge from '../../../components/Badge.jsx';
import AsyncState from '../../../components/AsyncState.jsx';
import s from './review.module.css';

export default function SummaryTab({ claim }) {
  const coverage = useApi(async () => {
    const [policy, cov] = await Promise.all([policyApi.byNumber(claim.policyNumber), policyApi.coverage(claim.policyNumber)]);
    return { policy, cov };
  }, [claim.policyNumber]);

  return (
    <div className={s.split}>
      <Card title="Claim form">
        <dl className="dl">
          <dt>Policyholder</dt><dd>{claim.policyholderName}</dd>
          <dt>Patient</dt><dd>{claim.patient.name} ({claim.patient.relation})</dd>
          <dt>Hospital</dt><dd>{claim.hospital}</dd>
          <dt>Admission</dt><dd>{date(claim.admissionDate)}</dd>
          <dt>Discharge</dt><dd>{date(claim.dischargeDate)}</dd>
          <dt>Diagnosis</dt><dd>{claim.diagnosis}</dd>
          <dt>Treatment type</dt><dd>{claim.treatmentType}</dd>
          <dt>Claimed amount</dt><dd className="num">{money(claim.claimedAmount)}</dd>
          <dt>Filed</dt><dd>{dateTime(claim.createdAt)}</dd>
          <dt>Last update</dt><dd>{dateTime(claim.updatedAt)}</dd>
        </dl>
      </Card>
      <Card title="Policy coverage" padded={false}>
        <AsyncState {...coverage} onRetry={coverage.reload}>
          {({ policy, cov }) => (
            <div>
              <div style={{ padding: 'var(--space-4) var(--space-5)' }}>
                <dl className="dl">
                  <dt>Plan</dt><dd>{policy.plan} · <span className="mono">{policy.policyNumber}</span></dd>
                  <dt>Validity</dt><dd>{date(cov.validFrom)} – {date(cov.validTo)}</dd>
                  <dt>Sum insured</dt><dd className="num">{money(cov.sumInsured)} ({money(policy.remainingBalance)} left)</dd>
                  <dt>Deductible / co-pay</dt><dd className="num">{money(cov.deductible)} / {cov.coPayPercent}%</dd>
                </dl>
              </div>
              <Table
                dense
                rowKey="treatment"
                caption="Coverage by treatment"
                columns={[
                  {
                    key: 'treatment', header: 'Treatment',
                    render: (r) => (
                      <span className={r.treatment === claim.treatmentType ? 'strong' : undefined}>
                        {r.treatment}{r.treatment === claim.treatmentType && <span className="xsmall muted"> ← this claim</span>}
                      </span>
                    ),
                  },
                  { key: 'covered', header: 'Covered', render: (r) => (r.covered ? <Badge tone="success" glyph="✓">Yes</Badge> : <Badge tone="danger" glyph="✕">No</Badge>) },
                  { key: 'subLimit', header: 'Sub-limit', align: 'right', render: (r) => <span className="num">{r.covered ? money(r.subLimit) : '—'}</span> },
                  { key: 'waitingPeriodDays', header: 'Waiting', align: 'right', render: (r) => `${r.waitingPeriodDays} d` },
                ]}
                rows={cov.treatments}
              />
              <div style={{ padding: 'var(--space-3) var(--space-5)' }} className="xsmall muted">
                Exclusions: {cov.exclusions.map((e) => e.label).join(' · ')}
              </div>
            </div>
          )}
        </AsyncState>
      </Card>
    </div>
  );
}
