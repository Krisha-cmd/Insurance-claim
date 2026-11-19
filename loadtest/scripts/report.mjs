#!/usr/bin/env node
// Turns one load-test run into a Markdown report with Mermaid charts (GitHub and VS Code render them).
//
//   node report.mjs <results-dir> <report-dir>
//
// <results-dir> holds raw.csv (k6 --out csv), summary.json (handleSummary) and, on Kubernetes, hpa.csv
// (watch-hpa.sh). Writes <report-dir>/report.md, and copies summary.json and hpa.csv next to it so the report
// can be checked later. raw.csv is too big for Git and stays behind.
import { createReadStream, existsSync, mkdirSync, readFileSync, copyFileSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { join } from 'node:path';

const [resultsDir, reportDir] = process.argv.slice(2);
if (!resultsDir || !reportDir) {
  console.error('usage: node report.mjs <results-dir> <report-dir>');
  process.exit(2);
}
const BUCKET = 10; // seconds per point on the time charts

// ---------- read the raw samples ----------
function splitCsv(line) {
  const out = []; let cur = ''; let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"' && line[i + 1] === '"') { cur += '"'; i++; } else if (c === '"') quoted = false; else cur += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { out.push(cur); cur = ''; } else cur += c;
  }
  out.push(cur);
  return out;
}

const buckets = new Map(); // bucket index -> { reqs, failed, durations[], vus }
const endpoints = new Map(); // name -> { durations[], failed, statuses: Map }
let t0 = Infinity, tEnd = 0, col = null;
const bucket = (i) => {
  if (!buckets.has(i)) buckets.set(i, { reqs: 0, failed: 0, durations: [], vus: 0 });
  return buckets.get(i);
};
const endpoint = (n) => {
  if (!endpoints.has(n)) endpoints.set(n, { durations: [], failed: 0, statuses: new Map() });
  return endpoints.get(n);
};

// Streamed: a long run has millions of rows. k6 writes them in time order, so the first row is the start.
const allDurList = [];
const rl = createInterface({ input: createReadStream(join(resultsDir, 'raw.csv')), crlfDelay: Infinity });
for await (const line of rl) {
  if (!line) continue;
  const f = splitCsv(line);
  if (!col) { col = Object.fromEntries(f.map((h, i) => [h, i])); continue; }
  const metric = f[col.metric_name];
  if (metric !== 'http_req_duration' && metric !== 'http_req_failed' && metric !== 'vus') continue;
  const ts = Number(f[col.timestamp]);
  if (t0 === Infinity) t0 = ts;
  tEnd = Math.max(tEnd, ts);
  const value = Number(f[col.metric_value]);
  const b = bucket(Math.max(0, Math.floor((ts - t0) / BUCKET)));
  if (metric === 'vus') { b.vus = Math.max(b.vus, value); continue; }
  if (f[col.scenario] === 'setup' || f[col.group] === '::setup') continue; // the one-off sign-ins before the test
  const e = endpoint(f[col.name] || f[col.url] || '(unnamed)');
  if (metric === 'http_req_duration') {
    b.reqs++; b.durations.push(value); e.durations.push(value); allDurList.push(value);
    const status = f[col.status];
    e.statuses.set(status, (e.statuses.get(status) || 0) + 1);
  } else if (value === 1) { b.failed++; e.failed++; }
}
if (t0 === Infinity) {
  console.error(`No samples in ${join(resultsDir, 'raw.csv')}`);
  process.exit(1);
}

const summary = existsSync(join(resultsDir, 'summary.json')) ? JSON.parse(readFileSync(join(resultsDir, 'summary.json'), 'utf8')) : null;
const hpaRows = existsSync(join(resultsDir, 'hpa.csv'))
  ? readFileSync(join(resultsDir, 'hpa.csv'), 'utf8').trim().split(/\r?\n/).slice(1).map((l) => l.split(','))
  : [];

// ---------- numbers ----------
const pct = (sorted, p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)] : 0);
const sortNum = (a) => Float64Array.from(a).sort();
const r0 = (n) => Math.round(n);
const r1 = (n) => Math.round(n * 10) / 10;
const fmtDuration = (s) => (s >= 60 ? `${Math.floor(s / 60)} min ${r0(s % 60)} s` : `${r0(s)} s`);

const nBuckets = Math.max(...buckets.keys()) + 1;
const series = Array.from({ length: nBuckets }, (_, i) => {
  const b = buckets.get(i) || { reqs: 0, failed: 0, durations: [], vus: 0 };
  const d = sortNum(b.durations);
  return { t: i * BUCKET, vus: b.vus, rps: r1(b.reqs / BUCKET), p95: r0(pct(d, 95)),
    errPct: b.reqs ? r1((100 * b.failed) / b.reqs) : 0 };
});

const allDur = sortNum(allDurList);
const totalReqs = allDur.length;
const totalFailed = [...endpoints.values()].reduce((a, e) => a + e.failed, 0);
const elapsed = Math.max(1, tEnd - t0);

const epRows = [...endpoints.entries()].map(([name, e]) => {
  const d = sortNum(e.durations);
  const avg = d.length ? d.reduce((a, b) => a + b, 0) / d.length : 0;
  return { name, count: d.length, rps: r1(d.length / elapsed), avg: r0(avg), p50: r0(pct(d, 50)), p95: r0(pct(d, 95)),
    p99: r0(pct(d, 99)), max: r0(d.length ? d[d.length - 1] : 0), errPct: d.length ? r1((100 * e.failed) / d.length) : 0,
    statuses: [...e.statuses.entries()].sort().map(([s, n]) => `${s}×${n}`).join(' ') };
}).sort((a, b) => b.count - a.count);

// ---------- Mermaid ----------
// One series per chart in one solid blue that reads on light and dark pages (Mermaid's default is a pale lavender).
const THEME = '%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%';
const thin = (pts, max = 90) => (pts.length <= max ? pts : pts.filter((_, i) => i % Math.ceil(pts.length / max) === 0));
function lineChart(title, yLabel, pts, key) {
  const p = thin(pts);
  const top = Math.max(1, ...p.map((x) => x[key]));
  return ['```mermaid', THEME, 'xychart-beta', `    title "${title}"`,
    `    x-axis "elapsed seconds" 0 --> ${p[p.length - 1].t}`,
    `    y-axis "${yLabel}" 0 --> ${Math.ceil(top * 1.15)}`,
    `    line [${p.map((x) => x[key]).join(', ')}]`, '```'].join('\n');
}
function barChart(title, yLabel, labels, values) {
  return ['```mermaid', THEME, 'xychart-beta horizontal', `    title "${title}"`,
    `    x-axis [${labels.map((l) => `"${l.replace(/"/g, "'")}"`).join(', ')}]`,
    `    y-axis "${yLabel}" 0 --> ${Math.ceil(Math.max(1, ...values) * 1.15)}`,
    `    bar [${values.join(', ')}]`, '```'].join('\n');
}
const table = (head, body) => [`| ${head.join(' | ')} |`, `| ${head.map(() => '---').join(' | ')} |`,
  ...body.map((r) => `| ${r.join(' | ')} |`)].join('\n');
const details = (summaryText, body) => `<details><summary>${summaryText}</summary>\n\n${body}\n\n</details>`;

// ---------- thresholds ----------
let verdict = 'unknown (no summary.json)';
let thresholdTable = '';
if (summary) {
  const th = [];
  for (const [metric, m] of Object.entries(summary.metrics)) {
    for (const [expr, t] of Object.entries(m.thresholds || {})) th.push([`\`${metric}\``, `\`${expr}\``, t.ok ? '✅ pass' : '❌ fail']);
  }
  verdict = th.every((r) => r[2].includes('pass')) ? '✅ all thresholds passed' : '❌ some thresholds failed';
  thresholdTable = table(['Metric', 'Threshold', 'Result'], th);
}

// ---------- HPA ----------
let hpaSection = '_Not recorded: this run was not against Kubernetes (no `kubectl` context), so there are no HPAs to watch._';
if (hpaRows.length) {
  const tsList = [...new Set(hpaRows.map((r) => Number(r[0])))].sort((a, b) => a - b);
  const h0 = tsList[0];
  const byHpa = new Map();
  for (const [ts, name, cur, , cpu] of hpaRows) {
    if (!byHpa.has(name)) byHpa.set(name, new Map());
    byHpa.get(name).set(Number(ts), { cur: Number(cur) || 0, cpu: cpu === '' ? 0 : Number(cpu) });
  }
  const hpaTable = [];
  const charts = [];
  for (const [name, m] of [...byHpa.entries()].sort()) {
    const pts = tsList.map((ts) => ({ t: ts - h0, ...(m.get(ts) || { cur: 0, cpu: 0 }) }));
    const curs = pts.map((p) => p.cur);
    const minR = Math.min(...curs), maxR = Math.max(...curs), maxCpu = Math.max(...pts.map((p) => p.cpu));
    hpaTable.push([`\`${name}\``, minR, maxR, `${maxCpu} %`, maxR > minR ? '**scaled out**' : '—']);
    if (maxR > minR) {
      charts.push(`#### ${name}\n\n${lineChart(`${name}: pods running`, 'pods', pts, 'cur')}\n\n${lineChart(`${name}: CPU % of request`, 'CPU %', pts, 'cpu')}`);
    }
  }
  hpaSection = [
    'Each HPA adds pods when the average CPU goes above 70 % of the pod\'s CPU request, up to its maximum.',
    'The table shows the fewest and most pods seen during the run.', '',
    table(['HPA', 'Min pods seen', 'Max pods seen', 'Peak CPU', 'Result'], hpaTable), '',
    charts.length ? charts.join('\n\n') : '_No HPA scaled out during this run: the load stayed under the CPU target._',
  ].join('\n');
}

// ---------- write ----------
const meta = summary?.meta || {};
const startedAt = new Date(t0 * 1000).toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
const slowest = [...epRows].sort((a, b) => b.p95 - a.p95);
const md = `# Load test report

| | |
| --- | --- |
| **Started** | ${startedAt} |
| **Target** | \`${meta.baseUrl || '?'}\` |
| **Profile** | \`${meta.profile || '?'}\`${meta.writes === false ? ' (read-only)' : ''} |
| **Duration** | ${fmtDuration(elapsed)} |
| **Requests** | ${totalReqs.toLocaleString('en')} (${r1(totalReqs / elapsed)} per second on average) |
| **Failed** | ${totalFailed.toLocaleString('en')} (${totalReqs ? r1((100 * totalFailed) / totalReqs) : 0} %) |
| **Latency** | median ${r0(pct(allDur, 50))} ms · p95 ${r0(pct(allDur, 95))} ms · p99 ${r0(pct(allDur, 99))} ms |
| **Verdict** | ${verdict} |

${thresholdTable}

## Load over time

${lineChart('Virtual users', 'users', series, 'vus')}

${lineChart('Requests per second', 'req/s', series, 'rps')}

${lineChart('Response time, 95th percentile', 'ms', series, 'p95')}

${lineChart('Failed requests', '% of requests', series, 'errPct')}

${details(`Data for these charts (one row per ${BUCKET} s)`, table(['Elapsed s', 'Users', 'Req/s', 'p95 ms', 'Failed %'],
  series.map((s) => [s.t, s.vus, s.rps, s.p95, s.errPct])))}

## By endpoint

${barChart('Response time p95 by endpoint', 'ms', slowest.map((e) => e.name), slowest.map((e) => e.p95))}

${table(['Endpoint', 'Requests', 'Req/s', 'Avg ms', 'p50', 'p95', 'p99', 'Max', 'Failed %', 'Status codes'],
  epRows.map((e) => [`\`${e.name}\``, e.count, e.rps, e.avg, e.p50, e.p95, e.p99, e.max, e.errPct, e.statuses]))}

## Autoscaling (HPA)

${hpaSection}

---
_Generated by \`loadtest/scripts/report.mjs\` from the k6 results. The k6 summary (\`summary.json\`)${hpaRows.length ? ' and the HPA recording (`hpa.csv`)' : ''} are stored next to this file._
`;

mkdirSync(reportDir, { recursive: true });
writeFileSync(join(reportDir, 'report.md'), md);
for (const f of ['summary.json', 'hpa.csv']) if (existsSync(join(resultsDir, f))) copyFileSync(join(resultsDir, f), join(reportDir, f));
console.log(`Report: ${join(reportDir, 'report.md')}`);
