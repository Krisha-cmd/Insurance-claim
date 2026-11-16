import s from './Card.module.css';

export default function Card({ title, actions, children, padded = true, className = '', as: Tag = 'section', ...rest }) {
  return (
    <Tag className={`${s.card} ${className}`} {...rest}>
      {(title || actions) && (
        <header className={s.header}>
          {title && <h3 className={s.title}>{title}</h3>}
          {actions && <div className={s.actions}>{actions}</div>}
        </header>
      )}
      <div className={padded ? s.body : undefined}>{children}</div>
    </Tag>
  );
}
