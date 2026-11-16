import s from './Badge.module.css';

/** Generic tone badge. Always carries text; the optional glyph adds a non-colour cue. */
export default function Badge({ tone = 'neutral', glyph, children, title }) {
  return (
    <span className={`${s.badge} ${s[tone]}`} title={title}>
      {glyph && <span aria-hidden="true" className={s.glyph}>{glyph}</span>}
      {children}
    </span>
  );
}
