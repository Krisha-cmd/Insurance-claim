# Claims frontend

A React 18 + Vite single-page app for the medical insurance claims platform. One login page sends each user to one of two portals, based on their role:

- **Policyholder portal** (`/portal`): file claims, upload documents and track progress.
- **Adjuster / Ops console** (`/ops`): review and decide on claims, watch the 3PC settlement live, browse the audit trail, view stream analytics and toggle chaos faults.

The app talks only to the **API Gateway**, over REST/JSON. It also runs against a built-in **mock backend**, which is on by default, so you can click through every flow without any service running.

## Run it

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173, mocks on
```

```bash
npm run build        # static build in dist/
npm run preview      # serve dist/ locally
```

### Mock users

Mock mode only. The login page shows these as a hint.

| Username    | Password   | Role         | Notes |
|-------------|------------|--------------|-------|
| `patient1`  | `password` | Policyholder | Ananya Iyer, Family Floater Gold, 5 claims |
| `adjuster1` | `password` | Adjuster     | Priya Nair |
| `patient2`  | `password` | Policyholder | Rohan Mehta, Individual Silver, 7 claims (extra account) |

## Point it at a real backend

Every URL lives in **`src/config/api.js`**: the base URL and every endpoint path. No other file builds a URL. Values are resolved in this order:

1. **Runtime**: `window.__APP_CONFIG__`, set by `public/config.js`. This file loads before the bundle, so **the same Docker image works in every environment**. Overwrite or mount the file at deploy time:
   ```js
   window.__APP_CONFIG__ = { API_BASE_URL: "https://gateway.example.com", USE_MOCKS: "false" };
   ```
2. **Build time**: Vite environment variables, for example in `.env.local`:
   ```bash
   VITE_API_BASE_URL=http://localhost:8080
   VITE_USE_MOCKS=false
   ```
3. **Defaults**: `http://localhost:8080`, with mocks **on**.

In Kubernetes, mount a ConfigMap over `/usr/share/nginx/html/config.js`. nginx serves that file with `Cache-Control: no-store`.

## Docker

```bash
docker build -t claims-frontend ./frontend
docker run -p 8081:80 claims-frontend
# with a real backend:
docker run -p 8081:80 -v $(pwd)/my-config.js:/usr/share/nginx/html/config.js:ro claims-frontend
```

The image is a multi-stage build: Node builds the app, then nginx serves it on port 80. Unknown paths fall back to `index.html`, so deep links work.

## Route map

| Route | Who | Page |
|---|---|---|
| `/login` | everyone | Sign in (`?expired=1` shows the "session expired" notice) |
| `/portal` | policyholder | Overview: policy summary and recent claims |
| `/portal/policy` | policyholder | Policy details, coverage table, exclusions |
| `/portal/claims/new` | policyholder | File a claim: three-step wizard |
| `/portal/claims` | policyholder | My claims, with a status filter (`?status=`) |
| `/portal/claims/:id` | policyholder | Status timeline, documents, re-upload, outcome. Polls until the claim is final |
| `/ops` | adjuster | Claims queue, oldest first |
| `/ops/claims` | adjuster | All claims: search, status and date filters |
| `/ops/claims/:id?tab=` | adjuster | Review: `summary`, `documents`, `eligibility`, `assessment`, `decision`, `settlement`, `audit` |
| `/ops/analytics` | adjuster | KPIs, per-minute charts, fraud flags. Refreshes every 5 s |
| `/ops/audit` | adjuster | Searchable audit event log |
| `/ops/chaos` | adjuster | Fault toggles for the payment gateway and the coordinator |

Anyone who is not signed in is sent to `/login`. A signed-in user who opens the other role's portal is sent back to their own.

## How it fits together

```
src/
├── config/api.js        the only place with URLs: base URL, ENDPOINTS, timeouts, poll intervals
├── api/client.js        fetch wrapper: JSON, Bearer token, 15 s timeout, {status, message} errors,
│                        401 → clear session and go to /login?expired=1, pre-signed upload with progress
├── api/services.js      one function per endpoint (pages call these, never paths)
├── api/mocks/           in-memory backend, loaded only when USE_MOCKS is true (split into its own chunk)
│   ├── engine.js        state machine and 3PC simulator, which also writes audit events
│   ├── seed.js          2 policies, 12 claims built by running the engine over past timestamps
│   ├── analytics.js     1-minute windows: synthetic baseline plus real demo events, and fraud flags
│   └── index.js         handlers keyed by the same ENDPOINTS entries
├── auth/                AuthContext (token in sessionStorage), ProtectedRoute
├── components/          Button, Card, Table, StatusBadge, Badge, Timeline, Modal, FileDrop, EmptyState,
│                        Spinner, Toast, Tabs, AsyncState, CommentField
├── layouts/             PolicyholderLayout (guided), AdjusterLayout (dense), shared Shell
├── lib/                 formatting (₹1,23,456, 16 Nov 2026), status enum, hooks, audit text, upload
├── pages/               login/, policyholder/, adjuster/ (review/ holds the claim review tabs)
└── styles/              tokens.css (design tokens), global.css
```

### Uploads

`POST claims.uploadUrl(id)` returns `{ uploadUrl, documentId }`. The browser then sends the file with `PUT` straight to `uploadUrl`, with no auth header. It uses XHR here, because `fetch` cannot report upload progress.

### Rules the UI enforces

- An override, an amount adjustment or a decision can't be submitted until the justification comment has at least 10 characters. The mock backend checks this too.
- A decision needs a second confirmation on the page itself. It never uses `window.confirm`.
- Once a decision is recorded, the review actions are locked.

## Demo script (mock mode)

1. Sign in as `patient1`. Go to **File a claim**, fill in the wizard and attach any PDF/JPG/PNG for the three required document types. Submit.
   Within about 10 s, the claim detail page shows the documents verified, eligibility, assessment and then *Under review*. A file whose name contains `blurry` is rejected, which lets you try the re-upload flow. `CLM-24002` already has a rejected bill.
2. Sign out and sign in as `adjuster1`. The new claim is in the **Claims queue**. Adjust the amount, override eligibility, then **Approve** it. You are taken to the **Settlement** tab, which shows the 3PC run live.
3. Go to **Chaos Controls**. Turn on *Fail payment gateway* and approve another claim: Payment votes NO and the transaction aborts. Turn it off, turn on *Crash coordinator after PreCommit*, then **Retry settlement**: the coordinator goes down, recovers from its log and commits.
4. **Analytics** picks up these events in the current 1-minute window. **Fraud flags** lists `POL-2026-00852`, which has 3 claims within 10 minutes.

Mock data lives in memory, so a page reload resets it.

## Backend contract notes

- `GET /api/ops/chaos` (same path as the `POST`) returns the current state of each toggle: `{ PAYMENT: {mode, changedAt, changedBy, affectedClaimId}, COORDINATOR: {...} }`. The Chaos page needs it.
- Paged lists (`claims.queue`, `claims.all`, `audit.search`) return `{ items, page, size, total }`. `claims.mine` returns a plain array.
- `approval.settlement(id)` returns `null` when no settlement has started. Otherwise it returns `{ transactionId, attempt, phase: CAN_COMMIT|PRE_COMMIT|DO_COMMIT|DONE, outcome: IN_PROGRESS|COMMITTED|ABORTED, coordinator: UP|DOWN|RECOVERING, participants: [{name, vote, preCommitAck, doCommitAck, aborted}], log: [{at, message, level}], abortReason }`.
- `src/api/mocks/` is the reference for the response shapes of every other endpoint.
