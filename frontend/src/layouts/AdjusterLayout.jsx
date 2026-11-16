import Shell from './Shell.jsx';

const NAV = [
  { to: '/ops', label: 'Claims Queue', icon: '⧗', end: true },
  { to: '/ops/claims', label: 'All Claims', icon: '≡', end: true },
  { to: '/ops/analytics', label: 'Analytics', icon: '◫' },
  { to: '/ops/audit', label: 'Audit Log', icon: '⎘' },
  { to: '/ops/chaos', label: 'Chaos Controls', icon: '⚡' },
];

export default function AdjusterLayout() {
  return <Shell variant="dense" brand="CareClaim Ops" roleLabel="Adjuster console" nav={NAV} />;
}
