import Badge from './Badge.jsx';
import { STATUS_META } from '../lib/status.js';

export default function StatusBadge({ status }) {
  const meta = STATUS_META[status] || { label: status || 'Unknown', tone: 'neutral', glyph: '?' };
  return <Badge tone={meta.tone} glyph={meta.glyph}>{meta.label}</Badge>;
}
