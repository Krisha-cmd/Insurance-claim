import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { ResponsiveContainer, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip } from 'recharts';
import { analyticsApi } from '../../api/services.js';
import { POLL_INTERVAL_MS } from '../../config/api.js';
import { useApi, useInterval } from '../../lib/hooks.js';
import { money, percent, time, dateTime } from '../../lib/format.js';
import Card from '../../components/Card.jsx';
import Table from '../../components/Table.jsx';
import Badge from '../../components/Badge.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import s from './adjuster.module.css';

/** Read chart colours from design tokens so charts follow tokens.css. */
function useTokens() {
  return useMemo(() => {
    const cs = getComputedStyle(document.documentElement);
    const v = (n) => cs.getPropertyValue(n).trim();
    return {
      s1: v('--series-1'), s2: v('--series-2'), s3: v('--series-3'), s4: v('--series-4'),
      grid: v('--chart-grid'), muted: v('--text-muted'), surface: v('--surface'),
    };
  }, []);
}

const hhmm = (iso) => time(iso).slice(0, 5);

function ChartTooltip({ active, payload, label, series }) {
  if (!active || !payload?.length) return null;
  return (
    <div className={s.tooltip}>
      <strong>{hhmm(label)}–{hhmm(new Date(new Date(label).getTime() + 60000).toISOString())}</strong>
      <ul>
        {series.map((sr) => {
          const p = payload.find((x) => x.dataKey === sr.key);
          return <li key={sr.key}><span><span className={s.swatch} style={{ background: sr.color }} />{sr.label}</span><strong className="num">{p?.value ?? 0}</strong></li>;
        })}
      </ul>
    </div>
  );
}

function SeriesChart({ title, subtitle, points, series, tokens }) {
  return (
    <Card title={title} actions={<span className="xsmall muted">{subtitle}</span>}>
      <div className={s.legend} aria-hidden="true">
        {series.map((sr) => <span key={sr.key}><span className={s.swatch} style={{ background: sr.color }} />{sr.label}</span>)}
      </div>
      <div className={s.chartBox} role="img" aria-label={`${title}: line chart per 1-minute window. A table view follows.`}>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={points} margin={{ top: 8, right: 16, bottom: 0, left: -16 }}>
            <CartesianGrid stroke={tokens.grid} vertical={false} />
            <XAxis dataKey="windowStart" tickFormatter={hhmm} tick={{ fontSize: 11, fill: tokens.muted }} tickLine={false} axisLine={{ stroke: tokens.grid }} minTickGap={24} />
            <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: tokens.muted }} tickLine={false} axisLine={false} width={40} />
            <Tooltip content={<ChartTooltip series={series} />} cursor={{ stroke: tokens.muted, strokeDasharray: '3 3' }} />
            {series.map((sr) => (
              <Line key={sr.key} type="monotone" dataKey={sr.key} name={sr.label} stroke={sr.color} strokeWidth={2} dot={false}
                activeDot={{ r: 4, stroke: tokens.surface, strokeWidth: 2 }} isAnimationActive={false} />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
      <details className={s.tableToggle}>
        <summary>View as table</summary>
        <Table
          dense
          rowKey="windowStart"
          caption={title}
          rows={points.slice().reverse()}
          columns={[
            { key: 'windowStart', header: 'Window', render: (p) => `${hhmm(p.windowStart)}` },
            ...series.map((sr) => ({ key: sr.key, header: sr.label, align: 'right' })),
          ]}
        />
      </details>
    </Card>
  );
}

function Kpi({ label, value, sub }) {
  return (
    <div className={s.kpi}>
      <div className={s.kpiLabel}>{label}</div>
      <div className={s.kpiValue}>{value}</div>
      <div className={s.kpiSub}>{sub}</div>
    </div>
  );
}

export default function AnalyticsPage() {
  const tokens = useTokens();
  const data = useApi(async () => {
    const [summary, byStatus, aborts, flags] = await Promise.all([
      analyticsApi.summary(),
      analyticsApi.series('claims_by_status'),
      analyticsApi.series('aborts'),
      analyticsApi.fraudFlags(),
    ]);
    return { summary, byStatus, aborts, flags };
  }, []);
  useInterval(() => data.reload({ silent: true }), POLL_INTERVAL_MS);

  const statusSeries = [
    { key: 'submitted', label: 'Submitted', color: tokens.s1 },
    { key: 'approved', label: 'Approved', color: tokens.s2 },
    { key: 'rejected', label: 'Rejected', color: tokens.s3 },
    { key: 'settled', label: 'Settled', color: tokens.s4 },
  ];
  const abortSeries = [
    { key: 'commits', label: 'Commits', color: tokens.s1 },
    { key: 'aborts', label: 'Aborts', color: tokens.s2 },
  ];

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Analytics</h1>
          <p>Live output of the stream processor, in 1-minute tumbling windows. Refreshes every {POLL_INTERVAL_MS / 1000} seconds.</p>
        </div>
        {data.data && <span className="xsmall muted">Updated {dateTime(data.data.summary.generatedAt ?? data.data.summary.windowEnd)}</span>}
      </div>
      <AsyncState {...data} onRetry={data.reload} isEmpty={() => false}>
        {({ summary, byStatus, aborts, flags }) => (
          <div className="stack">
            <div className={s.kpis}>
              <Kpi label="Claims in the last minute" value={summary.claimsLastMinute} sub={`Window ${hhmm(summary.windowStart)}–${hhmm(summary.windowEnd)}`} />
              <Kpi label="Approval rate" value={percent(summary.approvalRate)} sub="Approved ÷ decided, last 30 min" />
              <Kpi label="Average claimed amount" value={money(summary.avgClaimedAmount)} sub="Last 30 min" />
              <Kpi label="Settlement aborts" value={summary.settlementAborts} sub={`Last 30 min · ${summary.settlementCommits ?? '—'} commits`} />
            </div>
            <div className={s.charts}>
              <SeriesChart title="Claims by status" subtitle="Events per minute, last 30 min" points={byStatus.points} series={statusSeries} tokens={tokens} />
              <SeriesChart title="Settlement outcomes" subtitle="3PC transactions per minute" points={aborts.points} series={abortSeries} tokens={tokens} />
            </div>
            <Card title="Fraud flags" padded={false} actions={<span className="xsmall muted">Policies with more than 2 claims in 10 minutes</span>}>
              {flags.length === 0 ? <EmptyState title="No flags" message="No policy has crossed the threshold." /> : (
                <Table
                  rowKey="policyNumber"
                  caption="Fraud flags"
                  rows={flags}
                  columns={[
                    { key: 'policyNumber', header: 'Policy', render: (f) => <span className="mono strong">{f.policyNumber}</span> },
                    { key: 'holderName', header: 'Policyholder' },
                    { key: 'claimCount', header: 'Claims', align: 'right', render: (f) => <Badge tone="danger" glyph="!">{f.claimCount} in 10 min</Badge> },
                    { key: 'window', header: 'Window', render: (f) => <span className="small">{dateTime(f.firstClaimAt)} – {time(f.lastClaimAt)}</span> },
                    { key: 'claimIds', header: 'Claims', render: (f) => <span className="row" style={{ gap: 8 }}>{f.claimIds.map((id) => <Link key={id} to={`/ops/claims/${id}`} className="mono small">{id}</Link>)}</span> },
                  ]}
                />
              )}
            </Card>
          </div>
        )}
      </AsyncState>
    </div>
  );
}
