import { useId } from 'react';

export const MIN_COMMENT = 10;
export const commentValid = (c) => (c || '').trim().length >= MIN_COMMENT;

/** Required justification comment (at least 10 characters) with a live counter. */
export default function CommentField({ value, onChange, label = 'Justification comment', placeholder = 'Explain why you are making this change. This is saved to the audit trail.', autoFocus }) {
  const id = useId();
  const len = (value || '').trim().length;
  const ok = len >= MIN_COMMENT;
  return (
    <div className="field">
      <label htmlFor={id}>{label} <span aria-hidden="true">*</span></label>
      <textarea
        id={id}
        className="textarea"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        required
        aria-describedby={`${id}-count`}
        data-autofocus={autoFocus || undefined}
      />
      <span id={`${id}-count`} className="hint" style={{ color: ok ? 'var(--text-muted)' : 'var(--warning-fg)' }}>
        {ok ? `✓ ${len} characters` : `At least ${MIN_COMMENT} characters required (${len}/${MIN_COMMENT})`}
      </span>
    </div>
  );
}
