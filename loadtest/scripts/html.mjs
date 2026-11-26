// Renders one load-test run as a self-contained HTML page: inline SVG charts, no scripts or network needed.
// Used by report.mjs. One series per chart (the title names it), a data table under every chart, light and dark mode.

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const fmtNum = (n, d = 0) => Number(n).toLocaleString('en', { maximumFractionDigits: d });
const fmtT = (s) => `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')}`;

/** Round an axis maximum up to 1, 2, 2.5 or 5 × 10^n. */
function niceMax(v) {
  if (!(v > 0)) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (v <= m * p) return m * p;
  return 10 * p;
}

/** A line (or step) chart. pts: [{t, v}], t in seconds. */
function lineSvg(pts, { unit = '', step = false, max, integer = false } = {}) {
  const W = 640, H = 220, L = 52, R = 12, T = 12, B = 30;
  const tMax = Math.max(1, pts[pts.length - 1]?.t ?? 1);
  const yMax = max ?? niceMax(Math.max(...pts.map((p) => p.v), 0) * 1.05);
  const x = (t) => L + ((W - L - R) * t) / tMax;
  const y = (v) => T + (H - T - B) * (1 - v / yMax);
  let d = '';
  pts.forEach((p, i) => {
    if (i === 0) d += `M${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`;
    else if (step) d += `H${x(p.t).toFixed(1)}V${y(p.v).toFixed(1)}`;
    else d += `L${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`;
  });
  const yTicks = integer ? Array.from({ length: yMax + 1 }, (_, i) => i) : [0, 0.25, 0.5, 0.75, 1].map((f) => f * yMax);
  const dec = integer ? 0 : yMax < 1 ? 2 : yMax < 10 ? 1 : 0;
  const xTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * tMax);
  const grid = yTicks.map((v) => `<line class="grid" x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}"/>`
    + `<text class="tick" x="${L - 6}" y="${y(v) + 4}" text-anchor="end">${fmtNum(v, dec)}${esc(unit)}</text>`).join('');
  const xl = xTicks.map((t) => `<text class="tick" x="${x(t)}" y="${H - 8}" text-anchor="middle">${fmtT(t)}</text>`).join('');
  // Invisible hover targets, wider than the line, with a native tooltip.
  const hits = pts.map((p) => `<circle class="hit" cx="${x(p.t)}" cy="${y(p.v)}" r="7"><title>${fmtT(p.t)} · ${fmtNum(p.v, 2)}${esc(unit)}</title></circle>`).join('');
  return `<svg viewBox="0 0 ${W} ${H}" role="img">${grid}${xl}<path class="line" d="${d}"/>${hits}</svg>`;
}

/** Horizontal bars, one per label. */
function barsSvg(rows, unit) {
  const W = 640, rowH = 26, L = 250, R = 70, H = rows.length * rowH + 8;
  const vMax = niceMax(Math.max(...rows.map((r) => r.v), 0));
  const bars = rows.map((r, i) => {
    const w = Math.max(2, ((W - L - R) * r.v) / vMax);
    const yy = 4 + i * rowH;
    return `<text class="label" x="${L - 8}" y="${yy + 16}" text-anchor="end">${esc(r.label)}</text>`
      + `<rect class="bar" x="${L}" y="${yy + 4}" width="${w}" height="16" rx="3"><title>${esc(r.label)}: ${fmtNum(r.v)}${unit}</title></rect>`
      + `<text class="value" x="${L + w + 6}" y="${yy + 16}">${fmtNum(r.v)} ${unit}</text>`;
  }).join('');
  return `<svg viewBox="0 0 ${W} ${H}" role="img">${bars}</svg>`;
}

const table = (head, rows, numCols = []) => `<table><thead><tr>${head.map((h, i) => `<th${numCols.includes(i) ? ' class="num"' : ''}>${esc(h)}</th>`).join('')}</tr></thead>`
  + `<tbody>${rows.map((r) => `<tr>${r.map((c, i) => `<td${numCols.includes(i) ? ' class="num"' : ''}>${c}</td>`).join('')}</tr>`).join('')}</tbody></table>`;

function chartCard(title, desc, svg, dataHead, dataRows) {
  return `<section class="card"><h3>${esc(title)}</h3>${desc ? `<p class="muted">${esc(desc)}</p>` : ''}${svg}`
    + `<details><summary>Data</summary>${table(dataHead, dataRows, dataHead.map((_, i) => i))}</details></section>`;
}

export function renderHtml(r) {
  const { meta, startedAt, duration, totals, verdictOk, thRows, series, epRows, hpaData, bucket } = r;
  const tile = (label, value, sub = '', tone = '') => `<div class="tile ${tone}"><div class="tile-label">${esc(label)}</div><div class="tile-value">${value}</div>${sub ? `<div class="muted">${sub}</div>` : ''}</div>`;
  const seriesRows = (key) => series.map((s) => [fmtT(s.t), fmtNum(s[key], 1)]);
  const scaled = hpaData.filter((h) => h.maxR > h.minR);

  const hpaHtml = hpaData.length === 0
    ? '<p class="muted">Not recorded: this run had no Kubernetes context, so there were no HPAs to watch.</p>'
    : `<p>Each HPA adds pods when average CPU passes 70 % of the pod's CPU request, up to its maximum (dev: 2; approval: 1).</p>`
      + table(['HPA', 'Fewest pods', 'Most pods', 'Peak CPU (% of request)', 'Result'],
        hpaData.map((h) => [esc(h.name), h.minR, h.maxR, `${h.maxCpu} %`, h.maxR > h.minR ? '<strong>scaled out</strong>' : '—']), [1, 2, 3])
      + (scaled.length === 0 ? '<p class="muted">No HPA scaled out: the load stayed under the CPU target.</p>'
        : `<div class="grid2">${scaled.flatMap((h) => [
          chartCard(`${h.name}: pods running`, '', lineSvg(h.pts.map((p) => ({ t: p.t, v: p.cur })), { step: true, integer: true, max: Math.max(2, h.maxR) }),
            ['Elapsed', 'Pods'], h.pts.map((p) => [fmtT(p.t), p.cur])),
          chartCard(`${h.name}: CPU, % of request`, 'The HPA target is 70 %.', lineSvg(h.pts.map((p) => ({ t: p.t, v: p.cpu })), { unit: '%' }),
            ['Elapsed', 'CPU %'], h.pts.map((p) => [fmtT(p.t), p.cpu])),
        ]).join('')}</div>`);

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Load Test Report</title>
<style>
  :root { --bg:#f7f8fa; --card:#ffffff; --ink:#1b1f24; --muted:#5d6672; --line:#2f6fdf; --grid:#e3e6ea; --border:#d9dde3;
          --good:#1f7a3a; --bad:#b3261e; --chip-good:#e5f4ea; --chip-bad:#fbe9e7; }
  @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
          --bg:#111418; --card:#1a1e24; --ink:#e7eaee; --muted:#9aa3ad; --line:#6f9cf0; --grid:#2c323a; --border:#2f353d;
          --good:#6fcf8a; --bad:#f28b82; --chip-good:#16301f; --chip-bad:#3a1d1b; } }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--ink); font:15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 1120px; margin: 0 auto; padding: 24px 16px 48px; }
  h1 { font-size: 26px; margin: 0 0 4px; } h2 { font-size: 19px; margin: 32px 0 12px; } h3 { font-size: 15px; margin: 0 0 6px; }
  .muted { color: var(--muted); font-size: 13px; margin: 0 0 8px; }
  code { font-family: ui-monospace, Consolas, monospace; font-size: 13px; }
  .tiles { display:grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin: 16px 0; }
  .tile, .card { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
  .tile-label { color: var(--muted); font-size: 13px; } .tile-value { font-size: 24px; font-weight: 600; }
  .tile.good .tile-value { color: var(--good); } .tile.bad .tile-value { color: var(--bad); }
  .grid2 { display:grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 460px), 1fr)); gap: 12px; }
  svg { width: 100%; height: auto; display: block; }
  .line { fill: none; stroke: var(--line); stroke-width: 2; stroke-linejoin: round; }
  .grid { stroke: var(--grid); stroke-width: 1; } .tick, .label, .value { fill: var(--muted); font-size: 11px; }
  .label { fill: var(--ink); } .bar { fill: var(--line); } .hit { fill: transparent; } .hit:hover { fill: var(--line); fill-opacity: .25; }
  table { border-collapse: collapse; width: 100%; font-size: 13px; margin: 8px 0; }
  th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--border); } .num { text-align: right; font-variant-numeric: tabular-nums; }
  .scroll { overflow-x: auto; }
  details summary { cursor: pointer; color: var(--muted); font-size: 13px; margin-top: 6px; }
  .chip { display:inline-block; padding: 1px 8px; border-radius: 999px; font-size: 12px; font-weight: 600; }
  .chip.good { background: var(--chip-good); color: var(--good); } .chip.bad { background: var(--chip-bad); color: var(--bad); }
</style>
</head>
<body><main>
<h1>Load test report</h1>
<p class="muted">Claims platform · started ${esc(startedAt)} · profile <code>${esc(meta.profile || '?')}</code>${meta.writes === false ? ' (read-only)' : ''} · target <code>${esc(meta.baseUrl || '?')}</code></p>

<div class="tiles">
  ${tile('Verdict', verdictOk === null ? 'unknown' : verdictOk ? 'Passed' : 'Failed', 'every threshold', verdictOk === null ? '' : verdictOk ? 'good' : 'bad')}
  ${tile('Requests', fmtNum(totals.requests), `${fmtNum(totals.rps, 1)} per second on average`)}
  ${tile('Failed', `${fmtNum(totals.failedPct, 2)} %`, `${fmtNum(totals.failed)} requests`, totals.failedPct < 1 ? 'good' : 'bad')}
  ${tile('p95 latency', `${fmtNum(totals.p95)} ms`, `median ${fmtNum(totals.p50)} ms · p99 ${fmtNum(totals.p99)} ms`)}
  ${tile('Duration', esc(duration))}
  ${tile('HPAs that scaled out', hpaData.length ? String(scaled.length) : '—', hpaData.length ? `of ${hpaData.length}` : 'not recorded')}
</div>

<h2>Thresholds</h2>
${thRows.length ? table(['Metric', 'Threshold', 'Result'], thRows.map(([m, e, ok]) => [`<code>${esc(m)}</code>`, `<code>${esc(e)}</code>`, `<span class="chip ${ok ? 'good' : 'bad'}">${ok ? 'pass' : 'fail'}</span>`])) : '<p class="muted">No k6 summary found.</p>'}

<h2>Load over time</h2>
<p class="muted">One point per ${bucket} s. Hover a point for its value; open "Data" for the numbers.</p>
<div class="grid2">
  ${chartCard('Virtual users', 'Simulated people using the site at once.', lineSvg(series.map((s) => ({ t: s.t, v: s.vus }))), ['Elapsed', 'Users'], seriesRows('vus'))}
  ${chartCard('Requests per second', 'All REST calls through the load balancer.', lineSvg(series.map((s) => ({ t: s.t, v: s.rps }))), ['Elapsed', 'Req/s'], seriesRows('rps'))}
  ${chartCard('Response time, 95th percentile', '95 % of requests answered faster than this.', lineSvg(series.map((s) => ({ t: s.t, v: s.p95 })), { unit: ' ms' }), ['Elapsed', 'p95 ms'], seriesRows('p95'))}
  ${chartCard('Failed requests', 'Share of requests that errored or returned an unexpected status.', lineSvg(series.map((s) => ({ t: s.t, v: s.errPct })), { unit: '%', max: Math.max(5, niceMax(Math.max(...series.map((s) => s.errPct)))) }), ['Elapsed', 'Failed %'], seriesRows('errPct'))}
</div>

<h2>By endpoint</h2>
<section class="card"><h3>Response time p95, by endpoint</h3>
${barsSvg([...epRows].sort((a, b) => b.p95 - a.p95).map((e) => ({ label: e.name, v: e.p95 })), 'ms')}
<div class="scroll">${table(['Endpoint', 'Requests', 'Req/s', 'Avg ms', 'p50', 'p95', 'p99', 'Max', 'Failed %', 'Status codes'],
    epRows.map((e) => [`<code>${esc(e.name)}</code>`, fmtNum(e.count), e.rps, e.avg, e.p50, e.p95, e.p99, e.max, e.errPct, esc(e.statuses)]), [1, 2, 3, 4, 5, 6, 7, 8])}</div>
</section>

<h2>Autoscaling (HPA)</h2>
${hpaHtml}

<p class="muted" style="margin-top:32px">Generated by <code>loadtest/scripts/report.mjs</code> from k6 results${hpaData.length ? ' and a 10-second recording of every HPA' : ''}.</p>
</main></body></html>
`;
}
