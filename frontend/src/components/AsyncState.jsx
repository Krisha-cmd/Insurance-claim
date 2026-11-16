import Spinner from './Spinner.jsx';
import EmptyState from './EmptyState.jsx';
import Button from './Button.jsx';

/**
 * Renders loading / error / empty states, otherwise children.
 * `isEmpty` defaults to checking for an empty array.
 */
export default function AsyncState({ loading, error, data, onRetry, isEmpty, empty, children }) {
  if (loading && !data) return <Spinner />;
  if (error && !data) {
    return (
      <EmptyState
        tone="error"
        title="Couldn’t load this"
        message={error.message}
        action={onRetry && <Button variant="secondary" size="sm" onClick={() => onRetry()}>Try again</Button>}
      />
    );
  }
  const emptyCheck = isEmpty ?? ((d) => d == null || (Array.isArray(d) && d.length === 0) || (Array.isArray(d?.items) && d.items.length === 0));
  if (emptyCheck(data)) return empty ?? <EmptyState />;
  return typeof children === 'function' ? children(data) : children;
}
