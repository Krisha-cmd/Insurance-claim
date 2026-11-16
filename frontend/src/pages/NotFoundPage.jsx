import { Link } from 'react-router-dom';
import EmptyState from '../components/EmptyState.jsx';

export default function NotFoundPage() {
  return (
    <div style={{ paddingTop: 80 }}>
      <EmptyState title="Page not found" message="The page you were looking for doesn’t exist." action={<Link to="/">Go to your home page</Link>} />
    </div>
  );
}
