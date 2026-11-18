# Policy service

Serves policies, insured members and coverage. In the settlement three-phase commit (3PC) it is the
participant that **reserves the payout against the policy's remaining sum insured**.

```
REST :8080 (browser)                         gRPC :9090 (inside the cluster)
───────────────────────────                  ─────────────────────────────────────────────────
GET /api/policies/me                         serves: PolicyService
GET /api/policies/{policyNo}                           ValidateMember   ← Claim Intake (filing a claim)
GET /api/policies/{policyNo}/coverage                  GetPolicy / GetCoverage / GetBalance ← Eligibility, Assessment
                                                     SettlementParticipant ← Approval (coordinator)
                                             calls:  ClaimService.GetClaim → Claim Intake (in prepare)
```

The contracts are in [`backend/proto`](../../proto/src/main/proto) (`claims/policy/v1/policy.proto`).

## REST API

These are `ENDPOINTS.policy` in [`frontend/src/config/api.js`](../../../frontend/src/config/api.js). Each call
needs the login service's JWT. A policyholder is matched by the JWT's `policyholderId` (or, failing that,
by `sub` = `policyholder.user_id`).

| Method | Path | Who | Returns |
| --- | --- | --- | --- |
| GET | `/api/policies/me` | POLICYHOLDER | **One policy object**: the one in force today, otherwise the latest. `?all=true` returns all of the caller's policies, in the same order. 404 if they have none |
| GET | `/api/policies/{policyNo}` | owner or ADJUSTER | The policy, members, plan and balance. Another policyholder gets 403 |
| GET | `/api/policies/{policyNo}/coverage` | owner or ADJUSTER | Coverage rules, deductible, co-pay, room rent, waiting periods, exclusions |

The response shapes follow the frontend.

**Policy:**
- `policyNumber`, `holderId`, `holderName`, `plan` (the name), `status`
- `validFrom`, `validTo`, `sumInsured`
- `utilised`, `reserved`, `remainingBalance` (= sum insured − utilised − reserved)
- `deductible`, `coPayPercent`, `roomRentLimit`
- `members [{id, name, relation, dob}]`, with relation one of SELF / SPOUSE / CHILD / PARENT

**Coverage:**
- `treatments [{category, treatment, covered, subLimit, waitingPeriodDays, notes}]`. `treatment` is the
  label a claim's `treatmentType` carries (for example "Day care procedure"). `category` is the code
  Eligibility matches on.
- `exclusions [{keyword, label}]`: the plan's not-covered categories.

## Settlement participant (`LimitReservationParticipant`)

It is built on the shared [`participant`](../../participant) library, and its log is `limit_reservation`
(a `ParticipantTx`).

| Phase | What happens |
| --- | --- |
| CanCommit (prepare) | 1. Asks Claim Intake which policy the claim was filed under (`ClaimService.GetClaim`): CanCommit carries only the claim id and amount. 2. Votes YES only if the policy is ACTIVE and `sum_insured − utilised − reserved ≥ amount`. 3. On YES: `reserved += amount`. Otherwise NO with a reason, e.g. "Remaining sum insured ₹40,000 is less than ₹60,000" |
| PreCommit | Nothing more |
| DoCommit | `utilised += amount`, `reserved −= amount` |
| Abort | `reserved −= amount`. The base class calls this only for a YES vote, i.e. only if a reservation exists |
| Timeouts | PREPARED for 5 min with no PreCommit aborts; PRECOMMITTED for 2 min with no DoCommit commits |

**Concurrency.** `policy_balance` has a `version` column (optimistic lock). When two settlements prepare at
once against one policy, the second write fails its version check. The base class then retries that call
on fresh data (up to 3 times), so the loser sees the winner's reservation and votes NO for the real
reason. Two reservations that together exceed the balance can never both succeed.

## Data (Policy DB)

The tables follow the ER diagram: `policyholder`, `plan`, `coverage_rule`, `policy`, `insured_member`,
`policy_balance` (one row per policy) and `limit_reservation`. One difference: `limit_reservation` is keyed
by `tx_id` (the `ParticipantTx` key) rather than by a separate `id`.

### Seed (`V2__seed.sql`)

| What | Value |
| --- | --- |
| Policyholder | `patient1`, Asha Verma. `policyholder.id = a2b56321-c8b8-4159-93d6-3cf613b3c556` (the `policyholder_id` in login's seed); `user_id = 4e33233c-…` (login's patient1) |
| Plan | **Family Health Plus**: deductible ₹5,000, co-pay 10 %, room rent ₹5,000/day |
| Coverage | HOSPITALISATION ₹3,00,000 · SURGERY ₹2,00,000 · MATERNITY ₹75,000 (270-day wait) · DAYCARE ₹50,000 · COSMETIC not covered |
| `POL-2026-00017` | ACTIVE, 2026-01-01 → 2026-12-31, sum insured ₹5,00,000. Members: Asha Verma (SELF, `567dacf4-…`) and Vikram Verma (SPOUSE, `7971f862-…`) |
| `POL-2026-00018` | LAPSED, 2025-01-01 → 2025-12-31 (ended in the past, for eligibility tests). Member: Asha Verma (SELF, `6ee0f2b2-…`) |

All the fixed ids are listed at the top of the seed file.

## Configuration

| Variable | Default | On EKS |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | `localhost:5432/policy` | `policy-db` Secret |
| `JWT_JWKS_URI` | `http://localhost:8080/.well-known/jwks.json` | `http://login.claims.svc.cluster.local/.well-known/jwks.json` |
| `GRPC_PORT` | `9090` | expose it on the ClusterIP Service next to 80 → 8080 |
| `GRPC_CLAIM_TARGET` | `dns:///claim-intake:9090` | |
| `KAFKA_BOOTSTRAP` | empty (events only logged) | `platform-endpoints` ConfigMap |

**Events.** The participant emits `TX_VOTE` / `TX_PARTICIPANT_STATE` (participant = `policy`) on
`claim-events`, using the shared envelope.

## Build and test

```bash
cd backend
mvn -pl services/policy -am verify     # needs Docker (Testcontainers Postgres 16)
```

The tests run on one shared Spring context (`@PolicyTest`). Claim Intake's `ClaimService` is an in-process
gRPC fake, and Policy's own gRPC server is in-process too.

| Test | Covers |
| --- | --- |
| `PolicyApiIntegrationTest` | patient1 sees only their policies (frontend shape, `?all=true`); another policyholder gets 403 and has no policy (404 on `/me`); an adjuster sees everything; 404 and 401 |
| `ReservationIntegrationTest` | **two concurrent CanCommits of ₹60,000 against ₹1,00,000 left: exactly one YES, repeated 5 times** (each run hits the optimistic-lock retry); commit moves reserved → utilised; abort releases; lapsed policy / unknown claim vote NO |
| `LimitReservationParticipantContractTest` | the 11 shared participant cases, timeouts included |
| `PolicyGrpcIntegrationTest` | ValidateMember (valid, wrong member, someone else's policy, lapsed, unknown); GetPolicy / GetCoverage / GetBalance; a settlement over gRPC |
| `DomainTest` | balance arithmetic, treatment labels, ₹ formatting |
