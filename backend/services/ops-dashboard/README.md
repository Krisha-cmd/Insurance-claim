# Ops Dashboard backend

The adjuster and ops console's read API over the **`claims-analytics` DynamoDB table**: audit trail,
analytics and fraud flags. It also proxies the **chaos switches** for the demo. The Audit Flink job writes
the table, and this service only reads it.

```
browser ──REST──▶ Ops Dashboard ──Query──▶ DynamoDB claims-analytics (+ GSI1)   ◀── Audit Flink job
                       │──POST/GET /internal/chaos──▶ Payment (→ gateway simulator)
                       │──POST/GET /internal/chaos──▶ Approval (coordinator)
                       │──gRPC ClaimService.GetClaim──▶ Claim Intake   (owner check for the audit trail)
                       └──CHAOS_CHANGED──▶ Kafka claim-events
```

## REST API

These match `ENDPOINTS.analytics`, `ENDPOINTS.audit` and `ENDPOINTS.ops` in
[`frontend/src/config/api.js`](../../../frontend/src/config/api.js). Every call needs the login service's
JWT, and all of them are **ADJUSTER** only except the audit trail, which the claim's owner may also read.

| Method | Path | DynamoDB access | Response (the frontend's shape) |
| --- | --- | --- | --- |
| GET | `/api/analytics/summary` | `PK=METRIC#claims_overview#1m`, newest SK (`ScanIndexForward=false`, limit 1) for the last minute; `SK ≥ WINDOW#<now−30m>` for the 30-minute totals | `{windowStart, windowEnd, claimsLastMinute, approvalRate, avgClaimedAmount, settlementAborts, settlementCommits, lookbackMinutes, generatedAt}` |
| GET | `/api/analytics/series?metric=claims_by_status\|aborts&window=1m&from=` | `SK ≥ WINDOW#<from>` (default: the last 30 minutes) | `{metric, window, points}`, **one point per minute**; minutes with no data are 0 |
| GET | `/api/analytics/fraud-flags` | GSI1 `GSI1PK=TYPE#FraudFlag`, `GSI1SK ≥ now−24h`, newest first | `[{policyNumber, holderName, claimCount, firstClaimAt, lastClaimAt, claimIds, windowMinutes}]`, one per policy (its newest window) |
| GET | `/api/audit/claims/{id}` | `PK=CLAIM#<id>`, ascending | `[{id, claimId, type, actor{id,name,role}, at, before, after, comment, …}]` |
| GET | `/api/audit/events?type=&from=&to=&q=&page=&size=` | GSI1 `GSI1PK=TYPE#<type>`, `GSI1SK BETWEEN from AND to` | `{items, page, size, total}`, newest first |
| POST | `/api/ops/chaos` | | `{target: PAYMENT\|COORDINATOR, mode}` is forwarded; returns the combined status |
| GET | `/api/ops/chaos` | | `{PAYMENT: {mode, serviceMode, changedAt, changedBy, affectedClaimId, reachable, error}, COORDINATOR: {…}}` |

**Credentials.** On EKS they come from the **ops-dashboard IRSA role** created by Terraform, which allows
`dynamodb:Query`, `GetItem` and `DescribeTable` on the table and its indexes. There are no access keys.
There is also no `Scan`, so an audit search without a `type` runs one GSI1 query per known event type
(`AuditEventMapper.KNOWN_TYPES`) and merges the results.

### Translating audit events

The services emit their own event names in the shared envelope. The frontend (`AUDIT_TYPES` and
`describeEvent` in `frontend/src/lib/auditText.js`) uses the mock's names and reads particular `before`/`after`
fields. `AuditEventMapper` translates between them, and `eventType` always keeps the emitted name:

| Emitted | `type` in the response | `before` / `after` |
| --- | --- | --- |
| `CLAIM_CREATED` | `CLAIM_SUBMITTED` | after `{status, claimedAmount, hospital, claimNo}` |
| `CLAIM_STATUS_CHANGED` | `STATUS_CHANGED` | before `{status}`, after `{status}` |
| `ASSESSMENT_ADJUSTED` | (same) | before / after `{proposedAmount}` |
| `ASSESSMENT_COMPLETED` | (same) | after `{proposedAmount}` |
| `CLAIM_APPROVED` | (same) | after `{decision, approvedAmount, …}` |
| `TX_STARTED`, `SETTLEMENT_RETRIED`, `TX_ABORTED`, `COORDINATOR_CRASHED` | `SETTLEMENT_STARTED`, (same), `SETTLEMENT_ABORTED`, (same) | after `{transactionId, …change}` |
| `CLAIM_SETTLED` | `SETTLEMENT_COMMITTED` | after `{transactionId, amount, …}` |
| `TX_RECOVERY` | `COORDINATOR_RECOVERED` | after `{transactionId, resumedFrom}` |
| `CHAOS_CHANGED` | `CHAOS_TOGGLED` | |
| anything else | (same) | after = `change` |

A `type` filter accepts either name. A missing `actor.name` falls back to the service name for SYSTEM
actors and to the role otherwise.

### Chaos

| Request | Goes to |
| --- | --- |
| `{target: PAYMENT, mode: FAIL}` | Payment `POST /internal/chaos {mode: FAIL_AUTHORIZE, delayMs: 0}` |
| `{target: PAYMENT, mode: OFF}` | Payment `{mode: NONE}` |
| `{target: PAYMENT, mode: FAIL_CAPTURE \| SLOW, delayMs}` | Payment, as given |
| `{target: COORDINATOR, mode: CRASH_AFTER_PRECOMMIT \| CRASH_BEFORE_PRECOMMIT}` | Approval `POST /internal/chaos {mode}` |
| `{target: COORDINATOR, mode: OFF}` | Approval `{mode: NONE}` |

- **Status.** The response's status reads both services back. `mode` uses the frontend's names (OFF, FAIL,
  CRASH_…) and `serviceMode` the service's own.
- **When a service doesn't answer.** GET reports that target as `UNKNOWN` with `reachable: false`; POST
  answers 502.
- **Change tracking.** `changedAt` and `changedBy` record the last change made through this instance, in
  memory. `affectedClaimId` is not tracked yet.
- **Events.** Every change emits **`CHAOS_CHANGED`** `{target, mode, serviceMode}` on `claim-events`. Chaos
  events have no `claimId`, so the Flink job doesn't store them in the audit table; they're only in Kafka
  for now.

## Changes to the Audit Flink job

Two changes, both in `backend/analytics/audit-flink`, with tests:

- **Fraud flag items now get `GSI1PK=TYPE#FraudFlag`, `GSI1SK=<windowEnd>`.** Without them, the fraud flags
  couldn't be listed with a Query.
- **Audit items now keep `actor.name`.** The frontend shows it on every row.

## Configuration

| Variable | Default | On EKS |
| --- | --- | --- |
| `ANALYTICS_TABLE` | `claims-dev-analytics` | `platform-endpoints` ConfigMap |
| `AWS_REGION` | `ap-south-1` | ConfigMap |
| `APPROVAL_URL` / `PAYMENT_URL` | `http://approval` / `http://payment` | ClusterIP services in `claims` |
| `GRPC_CLAIM_TARGET` | `dns:///claim-intake:9090` | |
| `JWT_JWKS_URI` | `http://localhost:8080/.well-known/jwks.json` | `http://login.claims.svc.cluster.local/.well-known/jwks.json` |
| `KAFKA_BOOTSTRAP` | empty (events only logged) | ConfigMap |
| `claims.ops.lookback-minutes` | `30` | |
| `AWS_ENDPOINT_URL` | empty | LocalStack only |

## Build and test

```bash
cd backend
mvn -pl services/ops-dashboard -am verify     # needs Docker (LocalStack 3.8 DynamoDB)
```

**Test environment.**
- **DynamoDB:** LocalStack, with the table created exactly as Terraform's dynamodb module defines it (PK/SK
  + GSI1). It's seeded with the README's sample documents (audit events, window aggregates, fraud flags, a
  settlement view) in the **Flink job's exact attribute layout** (`support/SeedItems`).
- **Chaos endpoints:** two WireMock servers stand in for Approval's and Payment's `/internal/chaos`.
- **Owner check:** Claim Intake's `GetClaim` is an in-process fake.

**Shapes are checked against the frontend mocks.**
[`src/test/resources/shapes/frontend-mock-shapes.json`](src/test/resources/shapes/frontend-mock-shapes.json) is
the key structure of what `frontend/src/api/mocks` returns for each endpoint, generated by running the mocks
themselves:

```bash
cd frontend && node ../backend/services/ops-dashboard/src/test/resources/shapes/generate-shapes.mjs
```

Every response must contain every key the mock has, with a compatible type (null is always accepted).
An audit event's `before`/`after` depend on its type, so they're checked per type instead.

| Test | Covers |
| --- | --- |
| `OpsDashboardIntegrationTest` | summary (last minute and 30-minute totals); series for both metrics, gap-filled and with `from`; fraud flags via GSI1, one per policy; audit trail ascending in the frontend's vocabulary with the `before`/`after` fields `auditText.js` reads; owner vs other policyholder vs adjuster; search by type (either name), time bounds (including the millisecond edge), `q` and paging; role rules |
| `ChaosIntegrationTest` | PAYMENT FAIL reaches **Payment** as `FAIL_AUTHORIZE` (WireMock-verified) and returns both targets; COORDINATOR crash and OFF reach **Approval**; GET reads both; invalid targets or modes give 400; an unreachable service shows UNKNOWN on GET and 502 on POST; CHAOS_CHANGED is emitted |
