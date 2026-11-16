import s from './Spinner.module.css';

export default function Spinner({ size = 20, inline = false, label = 'Loading' }) {
  const el = <span className={s.spinner} style={{ width: size, height: size }} role="status" aria-label={label} />;
  if (inline) return el;
  return <div className={s.center}>{el}<span className="muted small">{label}…</span></div>;
}
