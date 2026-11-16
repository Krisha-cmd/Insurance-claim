import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { USE_MOCKS } from '../config/api.js';
import s from './Shell.module.css';

/** Sidebar + top bar. `variant` = 'guided' (policyholder) | 'dense' (adjuster). */
export default function Shell({ variant, brand, nav, roleLabel }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState(false);

  useEffect(() => { setOpen(false); }, [location.pathname]);

  const onLogout = async () => { await logout(); navigate('/login', { replace: true }); };

  return (
    <div className={`${s.shell} ${s[variant]}`}>
      <a href="#main" className={s.skip}>Skip to content</a>
      <aside className={`${s.sidebar} ${open ? s.open : ''}`} aria-label="Main navigation">
        <div className={s.brand}>
          <span className={s.logo} aria-hidden="true">✚</span>
          <div>
            <div className={s.brandName}>{brand}</div>
            <div className={s.brandSub}>{roleLabel}</div>
          </div>
        </div>
        <nav>
          <ul className={s.nav}>
            {nav.map((n) => (
              <li key={n.to}>
                <NavLink to={n.to} end={n.end} className={({ isActive }) => `${s.link} ${isActive ? s.active : ''}`}>
                  <span aria-hidden="true" className={s.icon}>{n.icon}</span>
                  {n.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </aside>
      {open && <div className={s.scrim} onClick={() => setOpen(false)} aria-hidden="true" />}

      <div className={s.main}>
        <header className={s.topbar}>
          <button type="button" className={s.menuBtn} onClick={() => setOpen((o) => !o)} aria-label="Toggle navigation" aria-expanded={open}>☰</button>
          <div className={s.topLeft}>
            {USE_MOCKS && <span className={s.mock} title="Data comes from the built-in mock backend">Mock data</span>}
          </div>
          <div className={s.user}>
            <div className={s.avatar} aria-hidden="true">{user?.name?.[0] ?? '?'}</div>
            <span className={s.userName}>{user?.name}</span>
            <button type="button" className={s.logout} onClick={onLogout}>Sign out</button>
          </div>
        </header>
        <main id="main" className={s.content} tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
