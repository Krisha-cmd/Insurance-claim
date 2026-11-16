import s from './EmptyState.module.css';

export default function EmptyState({ title = 'Nothing here yet', message, action, tone = 'neutral' }) {
  return (
    <div className={`${s.empty} ${tone === 'error' ? s.error : ''}`} role={tone === 'error' ? 'alert' : undefined}>
      <div className={s.icon} aria-hidden="true">{tone === 'error' ? '!' : '∅'}</div>
      <h4>{title}</h4>
      {message && <p className={s.msg}>{message}</p>}
      {action && <div className={s.action}>{action}</div>}
    </div>
  );
}
