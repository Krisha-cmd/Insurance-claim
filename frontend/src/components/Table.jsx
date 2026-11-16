import { useMemo, useState } from 'react';
import s from './Table.module.css';

/**
 * columns: [{ key, header, render?(row), sortValue?(row), sortable?, align?, width? }]
 * defaultSort: { key, dir: 'asc'|'desc' }
 */
export default function Table({ columns, rows, rowKey = 'id', onRowClick, defaultSort, dense = false, caption }) {
  const [sort, setSort] = useState(defaultSort || null);

  const sorted = useMemo(() => {
    if (!sort) return rows;
    const col = columns.find((c) => c.key === sort.key);
    if (!col) return rows;
    const val = col.sortValue || ((r) => r[col.key]);
    return [...rows].sort((a, b) => {
      const x = val(a); const y = val(b);
      if (x == null && y == null) return 0;
      if (x == null) return 1;
      if (y == null) return -1;
      const cmp = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y));
      return sort.dir === 'asc' ? cmp : -cmp;
    });
  }, [rows, sort, columns]);

  const toggle = (key) => setSort((cur) => (cur?.key === key ? { key, dir: cur.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }));

  return (
    <div className={s.wrap}>
      <table className={`${s.table} ${dense ? s.dense : ''}`}>
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr>
            {columns.map((c) => {
              const active = sort?.key === c.key;
              const ariaSort = active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none';
              return (
                <th key={c.key} style={{ width: c.width, textAlign: c.align }} aria-sort={c.sortable ? ariaSort : undefined}>
                  {c.sortable ? (
                    <button type="button" className={s.sortBtn} onClick={() => toggle(c.key)}>
                      {c.header}
                      <span aria-hidden="true" className={s.arrow}>{active ? (sort.dir === 'asc' ? '▲' : '▼') : '↕'}</span>
                    </button>
                  ) : c.header}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => (
            <tr
              key={row[rowKey]}
              className={onRowClick ? s.clickable : undefined}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter') onRowClick(row); } : undefined}
              tabIndex={onRowClick ? 0 : undefined}
            >
              {columns.map((c) => (
                <td key={c.key} style={{ textAlign: c.align }}>{c.render ? c.render(row) : row[c.key]}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
