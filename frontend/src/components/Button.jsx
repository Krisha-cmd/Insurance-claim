import { Link } from 'react-router-dom';
import s from './Button.module.css';
import Spinner from './Spinner.jsx';

/** variant: primary | secondary | ghost | danger; size: md | sm */
export default function Button({ variant = 'primary', size = 'md', loading = false, disabled, children, className = '', type = 'button', ...rest }) {
  return (
    <button
      type={type}
      className={`${s.btn} ${s[variant]} ${s[size]} ${className}`}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading && <Spinner size={14} inline />}
      <span>{children}</span>
    </button>
  );
}

/** A router link styled as a button (avoids nesting <button> inside <a>). */
export function ButtonLink({ to, variant = 'primary', size = 'md', className = '', children, ...rest }) {
  return <Link to={to} className={`${s.btn} ${s[variant]} ${s[size]} ${s.link} ${className}`} {...rest}>{children}</Link>;
}
