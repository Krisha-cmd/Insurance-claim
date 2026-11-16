import { format, formatDistanceToNowStrict, parseISO, isValid } from 'date-fns';

const inr = new Intl.NumberFormat('en-IN', {
  style: 'currency', currency: 'INR', maximumFractionDigits: 0,
});

/** ₹1,23,456 (Indian digit grouping). */
export function money(n) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—';
  return inr.format(Number(n));
}

function toDate(d) {
  if (!d) return null;
  const date = d instanceof Date ? d : typeof d === 'number' ? new Date(d) : parseISO(d);
  return isValid(date) ? date : null;
}

/** 16 Nov 2026 */
export function date(d) {
  const x = toDate(d);
  return x ? format(x, 'd MMM yyyy') : '—';
}

/** 16 Nov 2026, 14:05 */
export function dateTime(d) {
  const x = toDate(d);
  return x ? format(x, 'd MMM yyyy, HH:mm') : '—';
}

/** 14:05:09 */
export function time(d) {
  const x = toDate(d);
  return x ? format(x, 'HH:mm:ss') : '—';
}

/** "3 days", "12 minutes" */
export function age(d) {
  const x = toDate(d);
  return x ? formatDistanceToNowStrict(x) : '—';
}

export function percent(n, digits = 0) {
  if (n === null || n === undefined) return '—';
  return `${(Number(n) * 100).toFixed(digits)}%`;
}

export function humanize(s) {
  if (!s) return '';
  return s.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
}
