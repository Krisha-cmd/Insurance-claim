# Gateway simulator

A tiny stand-in for an external payment gateway that can be told to fail, for demos and for exercising the
Payment service's failure handling. It is Spring Boot web with **all state in memory**: no database, and a
restart forgets everything.

## API

| Method | Path | Body | Answer |
| --- | --- | --- | --- |
| POST | `/authorize` | `{paymentId, amount}` | `200 {authId}`. **Idempotent on `paymentId`**: the same payment always gets the same authId |
| POST | `/capture` | `{authId}` | `200 {captureRef}` (e.g. `UTR4821…`). **Idempotent on `authId`**. `404` for an unknown authId, `409` if it was voided |
| POST | `/void` | `{authId}` | `200`. Idempotent. `404` for an unknown authId, `409` if already captured |
| POST | `/chaos` | `{mode, delayMs}` | The new mode |
| GET | `/chaos` | | The current mode |

Errors use the shared `ApiError` shape `{status, message, path, timestamp}`. The endpoints aren't
authenticated, so keep the service cluster-internal.

## Chaos modes

| Mode | Effect | What Payment does |
| --- | --- | --- |
| `NONE` | Everything works | |
| `FAIL_AUTHORIZE` | `/authorize` answers **503** | Votes NO, so the settlement aborts |
| `FAIL_CAPTURE` | `/capture` answers **503**; authorize and void still work | Retries 3 times, acks the commit, then its recovery sweep keeps capturing until the mode is reset |
| `SLOW` | Every call waits `delayMs` (capped at 60 s) before answering | Times out after 3 s, so prepare votes NO |

```bash
curl -X POST localhost:8080/chaos -H 'Content-Type: application/json' -d '{"mode":"FAIL_CAPTURE"}'
curl -X POST localhost:8080/chaos -H 'Content-Type: application/json' -d '{"mode":"SLOW","delayMs":5000}'
curl -X POST localhost:8080/chaos -H 'Content-Type: application/json' -d '{"mode":"NONE"}'
```

Payment's `POST /internal/chaos` forwards to this endpoint (used by the Ops Dashboard).

## Build and test

```bash
cd backend
mvn -pl services/gateway-simulator -am verify
```

`GatewayControllerTest` has one case per mode. It also checks that authorize and capture are idempotent,
the void/capture conflicts, unknown authIds, the delay cap and input validation.
