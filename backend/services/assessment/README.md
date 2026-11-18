# Assessment service

Calculates the **proposed payout** for a claim with a step-by-step breakdown, and lets an adjuster
**adjust** it with a written justification. Approval settles the resulting **final amount**. This is one of the
two services with a CPU-based HPA.

```
gRPC :9090 (inside the cluster)                          REST :8080 (browser, via the ALB)
──────────────────────────────────────────────           ──────────────────────────────────────────
serves: AssessmentService.Assess          ← Claim Intake  GET /api/assessments/{claimId}        owner or ADJUSTER
        AssessmentService.GetFinalAmount  ← Approval      PUT /api/assessments/{claimId}/adjust ADJUSTER
calls:  PolicyService.GetCoverage / GetBalance  (policy)
        ClaimService.GetClaim / RecordAssessment (claim-intake)
        Kafka claim-events: ASSESSMENT_ADJUSTED
```

The contract is [`claims/assessment/v1/assessment.proto`](../../proto/src/main/proto/claims/assessment/v1/assessment.proto).

## The calculation (`AssessmentCalculator`)

A pure function with no Spring, no I/O and no clock. The Policy lookups happen before it, so the
**`claims.assessment.calculate`** timer measures CPU time only.

| Step | Formula | Notes |
| --- | --- | --- |
| 1 | `capped = min(claimed, subLimit(category))` | The rule is matched by code (`DAYCARE`) or by label (`Day care procedure`, which is what a claim carries), ignoring case. If no rule matches, there is no cap. A category that isn't covered caps at **0** |
| 2 | `afterDeductible = max(capped − deductible, 0)` | The breakdown shows the part of the deductible that was actually deducted |
| 3 | `copay = afterDeductible × copayPct / 100` | `copayPct` is a percentage, as Policy returns it (`"10.00"`) |
| 4 | `proposed = min(afterDeductible − copay, remainingBalance)` | `remainingBalance` comes from Policy's `GetBalance` (sum insured − utilised − reserved) |

Every amount is rounded to 2 decimals, **HALF_UP**, at the step that produces it. The co-pay is computed
exactly before it is rounded, so it is rounded only once.

Every step is stored (one column each) and returned as `BreakdownLine {label, amount}`. Running totals are
positive and deductions are negative, the same convention as the proto's `AssessmentLine`:

```
Claimed amount                                      60000.00
Capped at the Day care procedure sub-limit of 50000.00   50000.00
Deductible                                          -5000.00
After deductible                                    45000.00
Co-pay (10%)                                        -4500.00
Capped at the remaining sum insured of …            -x        (only when it applies)
Proposed amount                                     40500.00
```

## gRPC

| RPC | Behaviour |
| --- | --- |
| `Assess(AssessRequest{ClaimDetails})` | Claim Intake sends the whole claim, so the policy number, treatment category and claimed amount come from the request; Assessment never looks the claim up. **Idempotent:** there is one row per claim (`claim_id` is unique). Calling it again recalculates in place, because the balance may have changed, **unless the amount was adjusted**: then the stored assessment is returned unchanged, without calling Policy. `proposed_amount` in the reply is the amount in force (the adjusted one, if any), so Claim Intake never reverts an adjustment |
| `GetFinalAmount` | `final_amount`: the adjusted amount, else the proposed one. `NOT_FOUND` if the claim hasn't been assessed; Approval turns that into 409 "not assessed yet" |

Errors: a bad claim id or amount gives `INVALID_ARGUMENT`. An unknown policy gives `FAILED_PRECONDITION`.
Policy unreachable gives `UNAVAILABLE`. Claim Intake treats any of these as "assessment failed" and still
moves the claim to review.

Assess emits no event, because Claim Intake already emits `ASSESSMENT_COMPLETED` when it records the result.

## REST API

These are `ENDPOINTS.assessment` in [`frontend/src/config/api.js`](../../../frontend/src/config/api.js). Each call
needs the login service's JWT. Errors use the shared `ApiError` shape.

| Method | Path | Who | Result |
| --- | --- | --- | --- |
| GET | `/api/assessments/{claimId}` | the claim's owner (JWT `sub` = `submitted_by`, or `policyholderId` = the claim's policyholder) or ADJUSTER | The assessment, in the shape the adjuster's Assessment tab reads. **404** if the claim hasn't been assessed, **403** for another policyholder |
| PUT | `/api/assessments/{claimId}/adjust` | ADJUSTER | `{amount, comment}`. Returns the updated assessment |

**The response** has these fields:
- From `AssessmentTab.jsx`: `claimedAmount`, `subLimit`, `cappedAmount`, `deductible`, `afterDeductible`, `coPayPercent`, `coPayAmount`, `calculatedAmount` (what the calculation produced), `proposedAmount` (the amount in force), `adjustments [{from, to, comment, by{id, name, role}, at}]`, `assessedAt`.
- Added here: `finalAmount` (equal to `proposedAmount`), `adjusted`, `remainingBalance`, `currency`, `lines`, `policyNumber`, `treatmentCategory`, `updatedAt`.

**Adjust rules.** These checks run in order:

1. The amount must be more than 0 and have at most 2 decimals, and the comment must be at least 10 characters
   after trimming. Otherwise **400**.
2. The claim must have been assessed. Otherwise **409**.
3. The amount must not exceed the **claimed amount**. Otherwise **400**.
4. Claim Intake must report the claim as ELIGIBILITY_CHECKED, ASSESSED or UNDER_REVIEW, i.e. before a
   decision. Otherwise **409** "The amount can only be changed before a decision is made."

**Saving an adjustment.**
- **What is saved:** an `assessment_adjustment` row, plus `final_amount` and `adjusted = true`. The calculation itself is kept.
- **All or nothing:** inside the same transaction, `ClaimService.RecordAssessment` tells Claim Intake the new amount, which the claim then shows. If Claim Intake refuses (409) or is down (503), nothing is saved.
- **Event:** after the commit, **`ASSESSMENT_ADJUSTED`** is emitted. It carries `change {before, after}`, `amount`, `comment` and the adjuster as actor, which is the shape the Ops Dashboard's `AuditEventMapper` reads.
- **Concurrent edits:** a concurrent adjustment of the same assessment loses its version check and gets **409**.

## Data (Assessment DB)

[`V1__init.sql`](src/main/resources/db/migration/V1__init.sql):

| Table | Contents |
| --- | --- |
| `assessment` | The columns from issue #5 (`claim_id` UK, `claimed_amount`, `sub_limit_applied`, `after_cap`, `deductible`, `after_deductible`, `copay_pct`, `copay_amount`, `proposed_amount`, `final_amount`, `adjusted`, `version`, timestamps). Additions: `remaining_balance`, so every breakdown line can be rebuilt; the claim context (`policy_no`, `treatment_category`, `policyholder_id`, `submitted_by`), so reads and the owner check need no cross-service call; and `currency` |
| `assessment_adjustment` | `before_amount`, `after_amount`, `comment`, `adjuster_id`, `adjuster_name`, `adjusted_at` |

## Configuration

| Variable | Default | On EKS |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | `localhost:5432/assessment` | `assessment-db` Secret (Terraform `db_services` already includes `assessment`) |
| `GRPC_POLICY_TARGET` / `GRPC_CLAIM_TARGET` | `dns:///policy:9090` / `dns:///claim-intake:9090` | `assessment-config` ConfigMap |
| `GRPC_PORT` | `9090` | |
| `JWT_JWKS_URI` | `http://localhost:8080/.well-known/jwks.json` | `http://login.claims.svc.cluster.local/.well-known/jwks.json` |
| `KAFKA_BOOTSTRAP` | empty (events only logged) | `platform-endpoints` ConfigMap |
| `claims.assessment.call-timeout` | `3s` | deadline of each call to Policy and Claim Intake |

## Build and test

```bash
cd backend
mvn -pl services/assessment -am verify     # needs Docker (Testcontainers Postgres 16)
```

Policy and Claim Intake are **in-process gRPC servers** (`support/FakePolicyService`, `FakeClaimService`), so
the real gRPC client code is exercised. Each test registers its own policy number, so balances never leak
between tests.

| Test | Covers |
| --- | --- |
| `AssessmentCalculatorTest` | Parameterised: claimed below and equal to the deductible → 0; the sub-limit cap; no sub-limit; uncovered category → 0; the remaining-balance cap and an empty balance; no co-pay; rounding HALF_UP (12.505 → 12.51, 1543.20875 → 1543.21, 1000.005 → 1000.01). Also the breakdown lines and their order, matching by label or code, an unknown category, and negative inputs |
| `AssessmentGrpcIntegrationTest` | The reply and its lines; **Assess twice → one row**; re-assessing picks up a new balance; **after an adjustment Assess keeps `final_amount`** and doesn't call Policy; GetFinalAmount before and after an adjustment, and NOT_FOUND; INVALID_ARGUMENT / FAILED_PRECONDITION / UNAVAILABLE; the timer counts |
| `AssessmentApiIntegrationTest` | The response shape; owner, other policyholder and anonymous access; 404 before assessment; a successful adjustment, Claim Intake's `RecordAssessment` and the `ASSESSMENT_ADJUSTED` event; **an amount above the claimed amount → 400** (the claimed amount itself is allowed); zero, negative, missing, 3-decimal amounts and short comments → 400; policyholder → 403; after a decision → 409; Claim Intake down (503) or refusing (409) saves nothing |
