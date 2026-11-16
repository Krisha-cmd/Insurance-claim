import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext.jsx';
import { policyApi, claimsApi } from '../../api/services.js';
import { useApi } from '../../lib/hooks.js';
import Card from '../../components/Card.jsx';
import Button from '../../components/Button.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import PolicySummaryCard from './parts/PolicySummaryCard.jsx';
import ClaimList from './parts/ClaimList.jsx';

export default function OverviewPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const policy = useApi(() => policyApi.mine(), []);
  const claims = useApi(() => claimsApi.mine(), []);

  return (
    <div className="stack-lg">
      <div className="page-header" style={{ marginBottom: 0 }}>
        <div>
          <h1>Hello, {user.name.split(' ')[0]}</h1>
          <p>Here’s your policy and the latest on your claims.</p>
        </div>
        <Button onClick={() => navigate('/portal/claims/new')}>+ File a claim</Button>
      </div>

      <AsyncState {...policy} onRetry={policy.reload}>
        {(p) => <PolicySummaryCard policy={p} />}
      </AsyncState>

      <Card title="Recent claims" padded={false} actions={<Link to="/portal/claims" className="small">View all</Link>}>
        <AsyncState
          {...claims}
          onRetry={claims.reload}
          empty={<EmptyState title="No claims yet" message="When you file a claim, you’ll be able to track it here." action={<Button onClick={() => navigate('/portal/claims/new')}>File a claim</Button>} />}
        >
          {(list) => <ClaimList claims={list.slice(0, 4)} />}
        </AsyncState>
      </Card>
    </div>
  );
}
