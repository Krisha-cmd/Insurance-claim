# Eligibility service

A **stateless rule engine** that decides whether a claim is covered, plus the adjuster's **override** with a
required justification. It has no database. Claim Intake stores every result in its `eligibility_check` table:
the automatic one from its workflow, and an override through `RecordEligibility`.

```
gRPC :9090 (inside the cluster)                          REST :8080 (browser, via the ALB)
───────────────────────────────────────────────          ───────────────────────────────────────────
serves: EligibilityService.Check   ← Claim Intake         GET /api/eligibility/{claimId}           owner or ADJUSTER
calls:  PolicyService.GetPolicy / GetCoverage / GetBalance PUT /api/eligibility/{claimId}/override ADJUSTER
        ClaimService.GetClaim / RecordEligibility
        Kafka claim-events: ELIGIBILITY_OVERRIDDEN
```

## Rules

Every rule is a Spring bean that implements `EligibilityRule`, ordered with `@Order`. `EligibilityEngine` gets
them all injected, runs **every** rule and collects the results; it never stops at the first failure.
`eligible` means every rule passed. A rule that throws becomes a failed result ("Could not be evaluated: …"),
so a broken rule can never make a claim eligible or hide the other results. **To add a rule, add a bean.**
The engine doesn't change.

| `@Order` | Code | Label | Passes when |
| --- | --- | --- | --- |
| 10 | `POLICY_ACTIVE` | Policy is active | Policy's status is `ACTIVE` |
| 20 | `WITHIN_POLICY_PERIOD` | Admission date within the policy period | `start ≤ admission ≤ end` |
| 30 | `MEMBER_COVERED` | Patient is a member of the policy | the claim's `member_id` is one of the policy's members |
| 40 | `TREATMENT_COVERED` | Treatment type is covered | the plan has a rule for the claim's category (by code `DAYCARE` or label `Day care procedure`, ignoring case) and it is covered |
| 50 | `WAITING_PERIOD` | Waiting period completed | `admission − policy start ≥ the category's waiting days` (Maternity: 270) |
| 60 | `BALANCE_AVAILABLE` | Sum insured remaining | remaining balance > 0. Whether it covers the whole amount is Assessment's job: it caps the payout |

**Where the facts come from.** `RuleContext` is built before any rule runs, and the rules themselves do no
I/O:

| Part | Source | Fields |
| --- | --- | --- |
| claim | `ClaimDetails` | member id, admission date, treatment category, claimed amount |
| policy | Policy `GetPolicy` | status, period, members |
| coverage | Policy `GetCoverage` | rules with covered and waiting days |
| balance | Policy `GetBalance` | remaining |

A missing or unreadable fact makes the rules that need it fail with a message, never pass.

## gRPC `Check`

Claim Intake's workflow calls it with the claim's `ClaimDetails` once every required document is verified. It
returns `{eligible, rules[{rule, label, passed, detail}], checked_at}`. Nothing is stored or emitted here:
Claim Intake records the result and emits `ELIGIBILITY_CHECKED`.

Errors map as follows:
- An unknown policy gives `FAILED_PRECONDITION`.
- Policy unreachable gives `UNAVAILABLE`.

Claim Intake treats a failed check as "skip this step" and still sends the claim to review.

## REST API

These are `ENDPOINTS.eligibility` in [`frontend/src/config/api.js`](../../../frontend/src/config/api.js), in the
shape `EligibilityTab.jsx` reads.

| Method | Path | Who | Result |
| --- | --- | --- | --- |
| GET | `/api/eligibility/{claimId}` | the claim's owner (JWT `sub` = `submitted_by`, or `policyholderId`) or ADJUSTER | **Re-evaluates now** and merges the **latest override recorded in Claim Intake**. **404** until Claim Intake has recorded a first result, or if the claim doesn't exist; **403** for another policyholder |
| PUT | `/api/eligibility/{claimId}/override` | ADJUSTER | `{eligible, reason, comment}`. Returns the merged response |

**Response:**
```jsonc
{ "claimId": "…",
  "eligible": true,            // the verdict in force: the latest override, else rulesEligible
  "rulesEligible": false,      // what the rules say now
  "checks": [ { "rule": "POLICY_ACTIVE", "label": "Policy is active", "passed": false,
                "detail": "Policy POL-2026-00018 is LAPSED" }, … ],   // all six, in order
  "evaluatedAt": "…",
  "overridden": true,
  "override": { "eligible": true, "reason": "Documents reviewed manually",
                "comment": "…", "by": { "name": "Rohan Mehta", "role": "ADJUSTER" }, "at": "…" } }
```

**Override rules.** These checks run in order:

1. `eligible` and `reason` are required (the frontend offers a list of reasons). The comment must be at least
   10 characters after trimming. Otherwise **400**.
2. A result must already be recorded. Otherwise **409**.
3. The claim must be ELIGIBILITY_CHECKED, ASSESSED or UNDER_REVIEW, i.e. before a decision. Otherwise
   **409** "Eligibility can only be changed before a decision is made."

**What a valid override does.**
- **Records it in Claim Intake:** it calls `ClaimService.RecordEligibility` with `overridden = true`, the
  adjuster's verdict, the reason, the comment, the adjuster as actor, and the rules' current results.
- **Emits an event:** **`ELIGIBILITY_OVERRIDDEN`** with `change {before, after, reason}`, where `before` is
  the verdict that was in force, plus the comment. The Ops Dashboard maps it to `before.eligible` /
  `after.eligible` / `after.reason`, which is what `auditText.js` shows.
- **When Claim Intake refuses or is down:** the override fails (409 or 503), and nothing is emitted.

## Changes in other modules

| Where | Change |
| --- | --- |
| `proto/claims/claim/v1/claim.proto` | `ClaimDetails.latest_eligibility` (`EligibilityRecord`: eligible, overridden, reason, comment, checked_by, checked_at), set by `GetClaim`. `RecordEligibilityRequest.override_reason`. Both are additive, so existing clients are unaffected |
| Claim Intake | `V2__eligibility_override_reason.sql` adds `eligibility_check.override_reason`. `GetClaim` fills `latest_eligibility` from the newest `eligibility_check` row |
| Ops Dashboard | `AuditEventMapper` maps `ELIGIBILITY_OVERRIDDEN` explicitly |
| `k8s/base/eligibility` | The optional `eligibility-db` Secret reference is removed: the service has no database |

## Configuration

| Variable | Default | On EKS |
| --- | --- | --- |
| `GRPC_POLICY_TARGET` / `GRPC_CLAIM_TARGET` | `dns:///policy:9090` / `dns:///claim-intake:9090` | `eligibility-config` ConfigMap |
| `GRPC_PORT` | `9090` | |
| `JWT_JWKS_URI` | `http://localhost:8080/.well-known/jwks.json` | `http://login.claims.svc.cluster.local/.well-known/jwks.json` |
| `KAFKA_BOOTSTRAP` | empty (events only logged) | `platform-endpoints` ConfigMap |
| `claims.eligibility.call-timeout` | `3s` | deadline of each call to Policy and Claim Intake |

## Build and test

```bash
cd backend
mvn -pl services/eligibility -am verify     # no Docker needed: no database
```

| Test | Covers |
| --- | --- |
| `RulesTest` | One table per rule, with pass and fail rows: statuses; period boundaries (first and last day, a day outside); members of the other policy; covered, uncovered and unknown categories (by code and label); the 270-day maternity waiting period to the day; balances 0.01, 0, negative and unknown; missing dates |
| `EligibilityEngineTest` | **A claim on POL-2026-00018 (lapsed, ran through 2025) fails exactly POLICY_ACTIVE and WITHIN_POLICY_PERIOD and still reports all six**; all passed → eligible; nothing stops at the first failure; a throwing rule fails alone; a new `@Order`ed rule slots in without engine changes; no rules → never eligible |
| `EligibilityIntegrationTest` | Spring wires the six beans in order. `Check` for eligible and expired-policy claims, and its error codes. GET: the shape, owner / other / anonymous, 404s. **An override without a comment → 400** (also short, blank-padded, missing reason, missing verdict). **A valid override is recorded in Claim Intake (fake) and emits ELIGIBILITY_OVERRIDDEN**, and GET then merges it. Policyholder → 403; after a decision → 409; unchecked claim → 409; Claim Intake down → 503, nothing emitted |

Policy and Claim Intake are in-process gRPC fakes holding Policy's seed data (`POL-2026-00017` active,
`POL-2026-00018` lapsed). The Claim Intake fake turns a `RecordEligibility` into the next `GetClaim`'s
`latest_eligibility`, as the real service does (Claim Intake's `ClaimGrpcIntegrationTest` covers that side).
