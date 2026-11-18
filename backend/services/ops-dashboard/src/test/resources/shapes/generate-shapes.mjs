// Regenerates frontend-mock-shapes.json: the key structure of every response the frontend mocks
// (frontend/src/api/mocks) return for the endpoints Ops Dashboard serves. OpsDashboardIntegrationTest
// checks that every key in here is present in the real responses.
//
//   cd frontend && node ../backend/services/ops-dashboard/src/test/resources/shapes/generate-shapes.mjs
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Vite's ESM API from the frontend's node_modules (run this from frontend/).
const { createServer } = await import(pathToFileURL(join(process.cwd(), 'node_modules/vite/dist/node/index.js')).href);
const server = await createServer({ root: process.cwd(), server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' });
const mocks = await server.ssrLoadModule('/src/api/mocks/index.js');
const { ENDPOINTS: E } = await server.ssrLoadModule('/src/config/api.js');
const adjuster = 'mock-token-u-adj-1';
const call = (method, path, params = {}, body) => mocks.handleMock({ method, path, params, body, token: adjuster });

/** {key: shape} for objects, [shape-of-first] for arrays, "string" | "number" | "boolean" | "null" otherwise. */
function shape(v) {
  if (v === null || v === undefined) return 'null';
  if (Array.isArray(v)) {
    // Merge the keys of every element, so optional keys are not missed.
    const objects = v.filter((x) => x && typeof x === 'object' && !Array.isArray(x));
    if (objects.length) return [merge(objects.map(shape))];
    return v.length ? [shape(v[0])] : [];
  }
  if (typeof v === 'object') return Object.fromEntries(Object.keys(v).sort().map((k) => [k, shape(v[k])]));
  return typeof v;
}
function merge(shapes) {
  const out = {};
  for (const s of shapes) for (const [k, v] of Object.entries(s)) if (!(k in out) || out[k] === 'null') out[k] = v;
  return Object.fromEntries(Object.keys(out).sort().map((k) => [k, out[k]]));
}

const claims = await call('GET', E.claims.all, { size: 100 });
const claimId = claims.items[0].id;
const shapes = {
  summary: shape(await call('GET', E.analytics.summary)),
  seriesClaimsByStatus: shape(await call('GET', E.analytics.series, { metric: 'claims_by_status' })),
  seriesAborts: shape(await call('GET', E.analytics.series, { metric: 'aborts' })),
  fraudFlags: shape(await call('GET', E.analytics.fraudFlags)),
  auditTrail: shape(await call('GET', E.audit.trail(claimId))),
  auditSearch: shape(await call('GET', E.audit.search, {})),
  chaos: shape(await call('GET', E.ops.chaos)),
  chaosAfterSet: shape(await call('POST', E.ops.chaos, {}, { target: 'PAYMENT', mode: 'FAIL' })),
};
const out = fileURLToPath(new URL('./frontend-mock-shapes.json', import.meta.url));
writeFileSync(out, JSON.stringify(shapes, null, 2) + '\n');
console.log('wrote', out);
await server.close();
process.exit(0);
