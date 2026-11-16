import s from './Timeline.module.css';

/** items: [{ id, title, time, description?, tone?: 'neutral'|'success'|'danger'|'warning'|'info', meta? }] */
export default function Timeline({ items }) {
  return (
    <ol className={s.timeline}>
      {items.map((it, i) => (
        <li key={it.id} className={`${s.item} ${s[it.tone || 'neutral']}`}>
          <span className={s.dot} aria-hidden="true" />
          {i < items.length - 1 && <span className={s.line} aria-hidden="true" />}
          <div className={s.content}>
            <div className={s.head}>
              <span className={s.title}>{it.title}</span>
              <time className={s.time}>{it.time}</time>
            </div>
            {it.description && <div className={s.desc}>{it.description}</div>}
            {it.meta && <div className={s.meta}>{it.meta}</div>}
          </div>
        </li>
      ))}
    </ol>
  );
}
