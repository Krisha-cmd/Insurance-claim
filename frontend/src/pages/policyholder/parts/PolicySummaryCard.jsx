import { Link } from 'react-router-dom';
import Card from '../../../components/Card.jsx';
import Badge from '../../../components/Badge.jsx';
import { money, date } from '../../../lib/format.js';
import s from './parts.module.css';

export default function PolicySummaryCard({ policy, showLink = true }) {
  const usedPct = Math.round(((policy.sumInsured - policy.remainingBalance) / policy.sumInsured) * 100);
  return (
    <Card
      title={policy.plan}
      actions={<Badge tone={policy.status === 'ACTIVE' ? 'success' : 'neutral'} glyph={policy.status === 'ACTIVE' ? '✓' : '○'}>{policy.status === 'ACTIVE' ? 'Active' : policy.status}</Badge>}
    >
      <div className={s.policyGrid}>
        <div>
          <div className={s.kLabel}>Policy number</div>
          <div className="mono strong">{policy.policyNumber}</div>
        </div>
        <div>
          <div className={s.kLabel}>Valid</div>
          <div className="strong">{date(policy.validFrom)} – {date(policy.validTo)}</div>
        </div>
        <div>
          <div className={s.kLabel}>Sum insured</div>
          <div className={`${s.big} num`}>{money(policy.sumInsured)}</div>
        </div>
        <div>
          <div className={s.kLabel}>Remaining balance</div>
          <div className={`${s.big} num`}>{money(policy.remainingBalance)}</div>
        </div>
      </div>
      <div className={s.meter} role="img" aria-label={`${usedPct}% of sum insured used`}>
        <div className={s.meterFill} style={{ width: `${usedPct}%` }} />
      </div>
      <div className="row-between xsmall muted" style={{ marginTop: 6 }}>
        <span>{usedPct}% used this policy year</span>
        {showLink && <Link to="/portal/policy">View coverage →</Link>}
      </div>
    </Card>
  );
}
