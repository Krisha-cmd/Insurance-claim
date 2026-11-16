import { Link } from 'react-router-dom';
import StatusBadge from '../../../components/StatusBadge.jsx';
import { money, date } from '../../../lib/format.js';
import s from './parts.module.css';

/** Card-style claim list that reads well on a phone. */
export default function ClaimList({ claims }) {
  return (
    <ul className={s.claimList}>
      {claims.map((c) => (
        <li key={c.id}>
          <Link to={`/portal/claims/${c.id}`} className={s.claimRow}>
            <div className={s.claimMain}>
              <div className="row" style={{ gap: 8 }}>
                <span className="mono strong">{c.id}</span>
                <StatusBadge status={c.status} />
              </div>
              <div className={s.claimSub}>{c.patient.name} · {c.hospital}</div>
              <div className="xsmall muted">Filed {date(c.createdAt)} · {c.diagnosis}</div>
            </div>
            <div className={s.claimAmt}>
              <div className="num strong">{money(c.approvedAmount ?? c.claimedAmount)}</div>
              <div className="xsmall muted">{c.approvedAmount != null ? 'approved' : 'claimed'}</div>
            </div>
          </Link>
        </li>
      ))}
    </ul>
  );
}
