import { useId, useRef, useState } from 'react';
import s from './FileDrop.module.css';

const MAX_MB = 10;
const ACCEPT = '.pdf,.jpg,.jpeg,.png';

/** Drag-and-drop or click-to-browse. Calls onFiles(File[]) with validated files. */
export default function FileDrop({ onFiles, multiple = false, label = 'Drop a file here', disabled = false, compact = false }) {
  const [over, setOver] = useState(false);
  const [error, setError] = useState(null);
  const inputRef = useRef(null);
  const id = useId();

  const accept = (list) => {
    const files = [...list];
    const bad = files.find((f) => f.size > MAX_MB * 1024 * 1024);
    if (bad) { setError(`${bad.name} is larger than ${MAX_MB} MB.`); return; }
    const wrongType = files.find((f) => !/\.(pdf|jpe?g|png)$/i.test(f.name));
    if (wrongType) { setError(`${wrongType.name}: only PDF, JPG or PNG files are accepted.`); return; }
    setError(null);
    if (files.length) onFiles(multiple ? files : files.slice(0, 1));
  };

  return (
    <div>
      <label
        htmlFor={id}
        className={`${s.drop} ${over ? s.over : ''} ${compact ? s.compact : ''} ${disabled ? s.disabled : ''}`}
        onDragOver={(e) => { e.preventDefault(); if (!disabled) setOver(true); }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); if (!disabled) accept(e.dataTransfer.files); }}
      >
        <span aria-hidden="true" className={s.icon}>⇪</span>
        <span>{label} or <span className={s.link}>browse</span></span>
        <span className={s.hint}>PDF, JPG or PNG, up to {MAX_MB} MB</span>
        <input
          ref={inputRef}
          id={id}
          type="file"
          className="sr-only"
          accept={ACCEPT}
          multiple={multiple}
          disabled={disabled}
          onChange={(e) => { accept(e.target.files); e.target.value = ''; }}
        />
      </label>
      {error && <p className="xsmall" style={{ color: 'var(--danger-fg)', marginTop: 4 }} role="alert">{error}</p>}
    </div>
  );
}
