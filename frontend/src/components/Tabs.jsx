import { useRef } from 'react';
import s from './Tabs.module.css';

/** Accessible tab list (arrow keys move between tabs). tabs: [{ id, label, badge? }] */
export default function Tabs({ tabs, active, onChange, label = 'Sections' }) {
  const refs = useRef({});
  const onKey = (e, i) => {
    const dir = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
    if (!dir) return;
    e.preventDefault();
    const next = tabs[(i + dir + tabs.length) % tabs.length];
    onChange(next.id);
    refs.current[next.id]?.focus();
  };
  return (
    <div className={s.tabs} role="tablist" aria-label={label}>
      {tabs.map((t, i) => (
        <button
          key={t.id}
          ref={(el) => { refs.current[t.id] = el; }}
          type="button"
          role="tab"
          id={`tab-${t.id}`}
          aria-selected={active === t.id}
          aria-controls={`panel-${t.id}`}
          tabIndex={active === t.id ? 0 : -1}
          className={`${s.tab} ${active === t.id ? s.active : ''}`}
          onClick={() => onChange(t.id)}
          onKeyDown={(e) => onKey(e, i)}
        >
          {t.label}
          {t.badge}
        </button>
      ))}
    </div>
  );
}
