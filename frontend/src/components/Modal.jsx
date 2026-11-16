import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import s from './Modal.module.css';

/** Accessible modal: focus moves in on open, Escape closes, focus is trapped and restored on close. */
export default function Modal({ open, title, onClose, children, footer, width = 520 }) {
  const ref = useRef(null);
  const titleId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!open) return undefined;
    const prev = document.activeElement;
    const node = ref.current;
    const focusables = () => node.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])');
    (node.querySelector('[data-autofocus]') || focusables()[0] || node).focus();
    const onKey = (e) => {
      if (e.key === 'Escape') closeRef.current?.();
      if (e.key === 'Tab') {
        const els = [...focusables()].filter((el) => !el.disabled);
        if (!els.length) return;
        const first = els[0]; const last = els[els.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
      prev?.focus?.();
    };
  }, [open]);

  if (!open) return null;
  return createPortal(
    <div className={s.backdrop} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose?.(); }}>
      <div ref={ref} className={s.modal} role="dialog" aria-modal="true" aria-labelledby={titleId} style={{ maxWidth: width }} tabIndex={-1}>
        <header className={s.header}>
          <h3 id={titleId}>{title}</h3>
          <button type="button" className={s.close} onClick={onClose} aria-label="Close">×</button>
        </header>
        <div className={s.body}>{children}</div>
        {footer && <footer className={s.footer}>{footer}</footer>}
      </div>
    </div>,
    document.body,
  );
}
