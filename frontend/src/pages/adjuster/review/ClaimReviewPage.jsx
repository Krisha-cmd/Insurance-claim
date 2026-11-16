import { useCallback } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { claimsApi } from '../../../api/services.js';
import { POLL_INTERVAL_MS } from '../../../config/api.js';
import { useApi, useInterval } from '../../../lib/hooks.js';
import { money, age } from '../../../lib/format.js';
import { FINAL_STATUSES } from '../../../lib/status.js';
import StatusBadge from '../../../components/StatusBadge.jsx';
import Tabs from '../../../components/Tabs.jsx';
import AsyncState from '../../../components/AsyncState.jsx';
import { EligibilityBadge } from '../parts.jsx';
import SummaryTab from './SummaryTab.jsx';
import DocumentsTab from './DocumentsTab.jsx';
import EligibilityTab from './EligibilityTab.jsx';
import AssessmentTab from './AssessmentTab.jsx';
import DecisionTab from './DecisionTab.jsx';
import SettlementTab from './SettlementTab.jsx';
import AuditTab from './AuditTab.jsx';
import s from './review.module.css';

const TABS = [
  { id: 'summary', label: 'Summary', Component: SummaryTab },
  { id: 'documents', label: 'Documents', Component: DocumentsTab },
  { id: 'eligibility', label: 'Eligibility', Component: EligibilityTab },
  { id: 'assessment', label: 'Assessment', Component: AssessmentTab },
  { id: 'decision', label: 'Decision', Component: DecisionTab },
  { id: 'settlement', label: 'Settlement', Component: SettlementTab },
  { id: 'audit', label: 'Audit trail', Component: AuditTab },
];

export default function ClaimReviewPage() {
  const { id } = useParams();
  const [params, setParams] = useSearchParams();
  const active = TABS.some((t) => t.id === params.get('tab')) ? params.get('tab') : 'summary';
  const claim = useApi(() => claimsApi.byId(id), [id]);

  const refresh = useCallback(() => claim.reload({ silent: true }), [claim]);
  useInterval(refresh, POLL_INTERVAL_MS, Boolean(claim.data) && !FINAL_STATUSES.has(claim.data.status));

  const Active = TABS.find((t) => t.id === active).Component;

  return (
    <div>
      <nav className="small" style={{ marginBottom: 8 }}><Link to="/ops">← Claims queue</Link></nav>
      <AsyncState {...claim} onRetry={claim.reload}>
        {(c) => (
          <div className="stack">
            <header className={s.header}>
              <div>
                <div className="row" style={{ gap: 10 }}>
                  <h1 className="mono" style={{ fontSize: 'var(--fs-xl)' }}>{c.id}</h1>
                  <StatusBadge status={c.status} />
                  <EligibilityBadge value={c.eligibility} />
                </div>
                <p className="small secondary" style={{ marginTop: 4 }}>
                  {c.policyholderName} · <span className="mono">{c.policyNumber}</span> · {c.hospital} · filed {age(c.createdAt)} ago
                </p>
              </div>
              <div className={s.headerNums}>
                <div><div className="xsmall muted">Claimed</div><div className="num strong">{money(c.claimedAmount)}</div></div>
                <div><div className="xsmall muted">Proposed</div><div className="num strong">{money(c.proposedAmount)}</div></div>
                <div><div className="xsmall muted">Approved</div><div className="num strong">{money(c.approvedAmount)}</div></div>
              </div>
            </header>
            <Tabs tabs={TABS} active={active} onChange={(t) => setParams({ tab: t }, { replace: true })} label="Claim review sections" />
            <div role="tabpanel" id={`panel-${active}`} aria-labelledby={`tab-${active}`}>
              <Active claim={c} onClaimChanged={refresh} goTo={(t) => setParams({ tab: t })} />
            </div>
          </div>
        )}
      </AsyncState>
    </div>
  );
}
