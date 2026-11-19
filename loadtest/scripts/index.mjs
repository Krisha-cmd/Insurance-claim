#!/usr/bin/env node
// Rewrites <reports-dir>/README.md: one row per stored report, newest first.
//   node index.mjs <reports-dir>
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2];
const field = (md, label) => (md.match(new RegExp(`\\| \\*\\*${label}\\*\\* \\| (.*?) \\|`)) || [])[1] || '';

const rows = readdirSync(dir, { withFileTypes: true })
  .filter((d) => d.isDirectory() && existsSync(join(dir, d.name, 'report.md')))
  .map((d) => d.name).sort().reverse()
  .map((name) => {
    const md = readFileSync(join(dir, name, 'report.md'), 'utf8');
    const p95 = (field(md, 'Latency').match(/p95 (\d+ ms)/) || [])[1] || '';
    const scaled = (md.match(/\*\*scaled out\*\*/g) || []).length;
    return `| [${name}](${name}/report.md) | ${field(md, 'Profile')} | \`${field(md, 'Target').replace(/`/g, '')}\` | ${field(md, 'Requests').split(' (')[0]} | ${p95} | ${field(md, 'Failed').split(' (')[1]?.replace(')', '') || ''} | ${scaled || '—'} | ${field(md, 'Verdict')} |`;
  });

writeFileSync(join(dir, 'README.md'), `# Load test reports

Every run of \`loadtest/run.sh\` adds a folder here. Newest first.

| Report | Profile | Target | Requests | p95 | Failed | HPAs scaled out | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
${rows.join('\n')}
`);
