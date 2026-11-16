import Badge from '../../components/Badge.jsx';

export function EligibilityBadge({ value }) {
  if (value === 'ELIGIBLE') return <Badge tone="success" glyph="✓">Eligible</Badge>;
  if (value === 'INELIGIBLE') return <Badge tone="danger" glyph="✕">Not eligible</Badge>;
  return <Badge tone="neutral" glyph="○">Pending</Badge>;
}
