# Payment service

The settlement three-phase commit (3PC) participant that moves the money. It pays an approved claim through
an external payment gateway: it **authorizes** on prepare, **captures** on commit and **voids** on abort.
In this project the gateway is the [gateway simulator](../gateway-simulator), which can be told to fail.

```
Approval (coordinator) ──gRPC SettlementParticipant──▶ Payment ──REST──▶ gateway (simulator)
                                                          │              /authorize /capture /void /chaos
                                                          └──gRPC ClaimService.GetClaim──▶ Claim Intake
Ops Dashboard ──POST /internal/chaos──▶ Payment ──▶ gateway /chaos
```

Payment has **no public REST API**. The only HTTP endpoints are `/internal/chaos` (cluster-internal, not
routed by the ingress) and actuator.

## The participant (`PaymentParticipant`)

It is built on the shared [`participant`](../../participant) library, and its log is `payment_tx_log` (a
`ParticipantTx`).

| Phase | What happens |
| --- | --- |
| **CanCommit** (prepare) | 1. Asks Claim Intake who filed the claim (`ClaimService.GetClaim` → `submitted_by`); CanCommit carries only the claim id and amount. 2. Finds that user's payee account: none, or not verified → **NO**. 3. A claim that is already CAPTURED, or has another AUTHORIZED payment → **NO**. 4. Creates a `Payment` and calls **`authorize`** (3 s timeout). OK → AUTHORIZED with `auth_id`, vote **YES**. Gateway error or timeout → FAILED, vote **NO** with the gateway's reason |
| PreCommit | Nothing external |
| **DoCommit** | **`capture`**, up to 3 tries with backoff (200 ms, 400 ms). OK → CAPTURED with `gateway_ref`. If every try fails, the decision is still commit: the participant acks COMMITTED, the payment stays AUTHORIZED, an **ERROR** `CAPTURE PENDING` is logged and `PaymentRecoverySweep` keeps capturing |
| **Abort** | **`void`** if AUTHORIZED → VOIDED. A FAILED payment has nothing to void. A void that fails is retried by the sweep |
| Timeouts | PREPARED for 5 min with no PreCommit aborts (void); PRECOMMITTED for 2 min with no DoCommit commits (capture) |

**Payment attempts.** Every gateway call is recorded in `payment_attempt` with the action, result code
(`OK`, `HTTP_503`, `TIMEOUT`, `NETWORK`, …), error and duration. Gateway calls are idempotent (`authorize`
on the payment id, `capture`/`void` on the auth id), so repeating a phase is safe.

**`PaymentRecoverySweep`** runs every 30 s. A payment still AUTHORIZED whose settlement COMMITTED gets one
capture try; one whose settlement ABORTED gets one void try.

**A claim is paid at most once.** A partial unique index allows only one AUTHORIZED or CAPTURED payment per
claim. A settlement retried after an abort creates a new payment; the voided or failed ones stay as history.
(The ER diagram's plain `UNIQUE (claim_id)` would block that retry.)

### Known limit

Prepare runs in one database transaction. If the process dies after the gateway authorized but before that
transaction commits, the authorization exists only at the gateway. The coordinator then aborts the
settlement (its recovery aborts WAITING_VOTES), but nothing voids that orphan hold. Real gateways expire
uncaptured authorizations; the simulator keeps them in memory until it restarts.

## Gateway client

`HttpPaymentGatewayClient` uses Spring `RestClient` with a 1 s connect timeout and a 3 s read timeout.
Every failure becomes a `GatewayException`:

| Failure | Code | Retryable |
| --- | --- | --- |
| A 5xx or 429 | `HTTP_5xx`, `HTTP_429` | yes |
| A 4xx | `HTTP_4xx` | no |
| No answer within the read timeout, including while the body is being read | `TIMEOUT` | yes |
| Can't connect | `NETWORK` | yes |
| Answer without the expected field | `BAD_RESPONSE` | yes |

## Chaos

`POST /internal/chaos {mode, delayMs}` and `GET /internal/chaos` forward to the gateway's `/chaos`. The modes
are NONE, FAIL_AUTHORIZE, FAIL_CAPTURE and SLOW. The frontend's `/api/ops/chaos {target: PAYMENT, mode:
FAIL | OFF}` has to be translated by the Ops Dashboard backend (FAIL → FAIL_AUTHORIZE, OFF → NONE).

## Events (`claim-events`)

- `PAYMENT_AUTHORIZED`, `PAYMENT_CAPTURED` (with `paymentReference` = the gateway's UTR), `PAYMENT_VOIDED`,
  `PAYMENT_FAILED`, `PAYMENT_CAPTURE_PENDING`
- `TX_VOTE` / `TX_PARTICIPANT_STATE` (participant = `payment`)

All use the shared envelope and are sent after the commit.

## Data (Payments DB)

| Table | Contents |
| --- | --- |
| `payee_account` | Only a masked account number, plus the IFSC. The seed adds patient1's verified account: `XXXXXX4321`, `HDFC0001234`, `user_id = 4e33233c-…` (login's patient1) |
| `payment` | Beyond the ER diagram: `auth_id`, `updated_at`, `version` |
| `payment_attempt` | One row per gateway call (`duration_ms` added) |
| `payment_tx_log` | Keyed by `tx_id` (`ParticipantTx`), with `payment_id` |

## Configuration

| Variable / property | Default | |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | `localhost:5432/payment` | `payment-db` Secret on EKS |
| `GATEWAY_BASE_URL` | `http://gateway-simulator` | |
| `claims.payment.gateway.read-timeout` | `3s` | a slower gateway counts as failed |
| `claims.payment.gateway.capture-attempts` / `capture-backoff` | `3` / `200ms` | in DoCommit |
| `claims.payment.recovery-interval` | `PT30S` | the capture/void sweep |
| `GRPC_PORT` | `9090` | expose it on the ClusterIP Service |
| `GRPC_CLAIM_TARGET` | `dns:///claim-intake:9090` | |
| `KAFKA_BOOTSTRAP` | empty (events only logged) | |

The coordinator's DoCommit deadline is 5 s. Capture retries normally fit, because a 503 comes back
quickly. Against a gateway that times out on every try (3 × 3 s), the coordinator gives up first and
resends DoCommit later. The participant is idempotent, so that's safe.

## Build and test

```bash
cd backend
mvn -pl services/payment -am verify     # needs Docker (Testcontainers Postgres 16)
```

**WireMock** stands in for the gateway. Its responses echo the request (the authId contains the paymentId),
so tests can tell calls apart. Claim Intake is an in-process gRPC fake.

| Test | Covers |
| --- | --- |
| `PaymentParticipantIntegrationTest` | **YES path** (authorize → capture, no capture on PreCommit, no second capture on a repeated DoCommit); **FAIL_AUTHORIZE → NO** + FAILED + attempt row; slow gateway → TIMEOUT → NO; **abort after YES → void**; **capture fails twice, succeeds the third time → CAPTURED** (attempts `HTTP_503, HTTP_503, OK`); capture never succeeds → committed + CAPTURE_PENDING, then the sweep captures once the gateway is back; never paid twice, but retryable after an abort; no or unverified payee; the sweep voids an aborted payment |
| `PaymentParticipantContractTest` | the 11 shared participant cases |
| `HttpPaymentGatewayClientTest` | 5xx / 4xx / timeout / connection refused / malformed answer → codes and retryability |
| `InternalChaosControllerTest` | forwarding to the gateway's `/chaos`, validation, unreachable gateway → 502 |
