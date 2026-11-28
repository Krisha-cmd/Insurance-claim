import { useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext.jsx';
import { homeFor } from '../../auth/session.js';
import { USE_MOCKS } from '../../config/api.js';
import Button from '../../components/Button.jsx';
import s from './LoginPage.module.css';

export default function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [form, setForm] = useState({ username: '', password: '' });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [loading, setLoading] = useState(false);

  if (user) return <Navigate to={homeFor(user.role)} replace />;

  const onSubmit = async (e) => {
    e.preventDefault();
    const errs = {};
    if (!form.username.trim()) errs.username = 'Enter your username.';
    if (!form.password) errs.password = 'Enter your password.';
    setErrors(errs);
    setSubmitError(null);
    if (Object.keys(errs).length) return;
    setLoading(true);
    try {
      const u = await login(form.username.trim(), form.password);
      navigate(homeFor(u.role), { replace: true });
    } catch (err) {
      setSubmitError(err.message || 'Sign in failed.');
      setLoading(false);
    }
  };

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  return (
    <div className={s.page}>
      <div className={s.panel}>
        <div className={s.brand}>
          <span className={s.logo} aria-hidden="true">✚</span>
          <span className={s.brandName}>CareClaim</span>
        </div>
        <h1 className={s.title}>Sign in</h1>
        <p className={s.subtitle}>File and track medical claims, or review them as an adjuster.</p>

        {params.get('expired') && !submitError && (
          <div className="notice warning" role="status" style={{ marginBottom: 16 }}>Your session expired. Please sign in again.</div>
        )}
        {submitError && <div className="notice danger" role="alert" style={{ marginBottom: 16 }}>{submitError}</div>}

        <form onSubmit={onSubmit} noValidate className="stack">
          <div className="field">
            <label htmlFor="username">Username</label>
            <input
              id="username" className="input" autoComplete="username" autoFocus
              value={form.username} onChange={set('username')}
              aria-invalid={Boolean(errors.username)} aria-describedby={errors.username ? 'username-err' : undefined}
            />
            {errors.username && <span id="username-err" className="error">{errors.username}</span>}
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input
              id="password" type="password" className="input" autoComplete="current-password"
              value={form.password} onChange={set('password')}
              aria-invalid={Boolean(errors.password)} aria-describedby={errors.password ? 'password-err' : undefined}
            />
            {errors.password && <span id="password-err" className="error">{errors.password}</span>}
          </div>
          <Button type="submit" loading={loading} className={s.submit}>{loading ? 'Signing in…' : 'Sign in'}</Button>
        </form>

        {USE_MOCKS && (
          <div className={s.hint}>
            <strong>Mock mode.</strong> Try these accounts (password <code>password</code>):
            <ul>
              <li><button type="button" className={s.fill} onClick={() => setForm({ username: 'patient1', password: 'password' })}>patient1</button> policyholder</li>
              <li><button type="button" className={s.fill} onClick={() => setForm({ username: 'adjuster1', password: 'password' })}>adjuster1</button> adjuster</li>
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}
