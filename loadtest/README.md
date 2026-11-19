# Load tests

A load test pretends to be many people using the website at once. It shows how fast the system answers under
pressure, whether anything breaks, and whether Kubernetes adds pods (the HPAs) when it gets busy.

The tool is **[k6](https://grafana.com/docs/k6/latest/)**: the test is a small JavaScript file, and k6 runs it
with as many virtual users as you ask for.

## What it does

[`k6/claims-load.js`](k6/claims-load.js) makes the same REST calls as the website, through one address, like a
browser. Three kinds of user run at once:

| User | What it does | Services it loads |
| --- | --- | --- |
| **policyholder** (70 % of users) | Opens their policy, coverage, claims and one claim. One visit in ten files a new claim | Policy, Claim Intake, and the gRPC call from Claim Intake to Policy |
| **adjuster** (30 %) | Opens the review queue and searches claims | Claim Intake |
| **signIn** (a steady rate) | Signs in, checks `/me`, signs out | Login (bcrypt password checks use a lot of CPU) |

| Profile | Length | Load |
| --- | --- | --- |
| `smoke` | 1 min | 2 users + 1 sign-in/s. Checks the test and the system work |
| `load` | 10 min | ramp to 40 users + 5 sign-ins/s, hold 6 min, ramp down |
| `stress` | 10 min | ramp to 150 users + 15 sign-ins/s, to find the limit and make the HPAs scale out |

**Pass rules (thresholds):** under 1 % of requests fail, 95 % answer within 1 s (sign-in within 1.5 s), and over
99 % of checks pass. If a rule fails, k6 exits with code 99, but the report is still written.

## Run it

You need [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) and Node 20+. Run from the repository root.

```bash
# on AWS: the website address is the ingress ADDRESS (kubectl -n claims get ingress)
BASE_URL=http://<ingress-address> PROFILE=stress ./loadtest/run.sh

# locally: start the backend (RUN.md, part A2), then point the test at the Vite proxy
cd frontend && npm run dev:backend -- --host 127.0.0.1 &    # k6 needs the IPv4 address on Windows
cd .. && BASE_URL=http://127.0.0.1:5173 PROFILE=load ./loadtest/run.sh
```

Options: `WRITES=false` makes the test read-only (files no claims), `WATCH_HPA=false` skips the HPA recording,
and `KEEP_RAW=true` keeps the raw k6 samples in `loadtest/results/`.

## The report

Each run writes **`loadtest/reports/<date>-<profile>/report.md`** and adds a row to
[`reports/README.md`](reports/README.md). Commit the folder to keep the result. A report contains:

- the verdict and each threshold;
- graphs over time: virtual users, requests per second, p95 response time, and failed requests (Mermaid
  charts, which GitHub and VS Code draw; each has its data table underneath);
- a table per endpoint (requests, p50/p95/p99, failures, status codes) and a p95 bar chart;
- **autoscaling**: when `kubectl` can see the `claims` namespace, `run.sh` records every HPA every 10 s (and
  for 60 s after the test). The report lists the pods and CPU for each HPA, and graphs each HPA that scaled out.

`summary.json` (k6's own summary) and `hpa.csv` are saved next to the report. The raw samples are too big
for Git, so they are deleted unless you set `KEEP_RAW=true`.

## Autoscaling under test

Every service has an HPA ([`k8s/base/*/hpa.yaml`](../k8s/base)) that targets 70 % of its CPU request.
metrics-server is installed by `infra/terraform/platform`. Replica bounds per environment are in the overlays:

| | dev | prod |
| --- | --- | --- |
| login, claim-intake, policy, eligibility, assessment, payment, gateway-simulator, ops-dashboard, frontend | 1–2 pods | 2–6 pods |
| approval (3PC coordinator) | exactly 1 | exactly 1 |

To watch the scaling live during a run: `kubectl -n claims get hpa -w`.
