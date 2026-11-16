import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import s from './Toast.module.css';

const ToastContext = createContext(null);

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const seq = useRef(0);

  const dismiss = useCallback((id) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const push = useCallback((tone, message) => {
    const id = ++seq.current;
    setToasts((t) => [...t, { id, tone, message }]);
    setTimeout(() => dismiss(id), tone === 'error' ? 7000 : 4000);
  }, [dismiss]);

  const api = useMemo(() => ({
    success: (m) => push('success', m),
    error: (m) => push('error', m),
    info: (m) => push('info', m),
  }), [push]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className={s.region} aria-live="polite" aria-atomic="false">
        {toasts.map((t) => (
          <div key={t.id} className={`${s.toast} ${s[t.tone]}`} role={t.tone === 'error' ? 'alert' : 'status'}>
            <span aria-hidden="true" className={s.glyph}>{t.tone === 'success' ? '✓' : t.tone === 'error' ? '!' : 'i'}</span>
            <span className={s.msg}>{t.message}</span>
            <button type="button" className={s.close} onClick={() => dismiss(t.id)} aria-label="Dismiss">×</button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside ToastProvider');
  return ctx;
}
