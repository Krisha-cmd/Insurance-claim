import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Load data with loading/error state. `fn` returns a promise.
 * `reload({silent:true})` refreshes without flashing the loading state (for polling).
 */
export function useApi(fn, deps = []) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const seq = useRef(0);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps);

  const reload = useCallback(async ({ silent = false } = {}) => {
    const id = ++seq.current;
    if (!silent) setLoading(true);
    try {
      const result = await run();
      if (id === seq.current) { setData(result); setError(null); }
    } catch (err) {
      if (id === seq.current && !silent) setError(err);
    } finally {
      if (id === seq.current) setLoading(false);
    }
  }, [run]);

  useEffect(() => { reload(); }, [reload]);

  return { data, error, loading, reload, setData };
}

/** Call `cb` every `ms` while `enabled`. Pauses while the tab is hidden. */
export function useInterval(cb, ms, enabled = true) {
  const saved = useRef(cb);
  useEffect(() => { saved.current = cb; }, [cb]);
  useEffect(() => {
    if (!enabled) return undefined;
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') saved.current();
    }, ms);
    return () => clearInterval(id);
  }, [ms, enabled]);
}
