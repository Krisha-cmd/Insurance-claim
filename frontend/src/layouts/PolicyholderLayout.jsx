import Shell from './Shell.jsx';

const NAV = [
  { to: '/portal', label: 'Overview', icon: '⌂', end: true },
  { to: '/portal/policy', label: 'My Policy', icon: '▤' },
  { to: '/portal/claims/new', label: 'File a Claim', icon: '+' },
  { to: '/portal/claims', label: 'My Claims', icon: '≡', end: true },
];

export default function PolicyholderLayout() {
  return <Shell variant="guided" brand="CareClaim" roleLabel="Policyholder portal" nav={NAV} />;
}
