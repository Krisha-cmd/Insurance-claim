# Load test report

| | |
| --- | --- |
| **Started** | 2026-11-27 07:53:28 UTC |
| **Target** | `http://k8s-claims-8285bb1f19-797935702.ap-south-1.elb.amazonaws.com` |
| **Profile** | `stress` (read-only) |
| **Duration** | 10 min 0 s |
| **Requests** | 97,059 (161.8 per second on average) |
| **Failed** | 0 (0 %) |
| **Latency** | median 75 ms · p95 188 ms · p99 299 ms |
| **Verdict** | ✅ all thresholds passed |

| Metric | Threshold | Result |
| --- | --- | --- |
| `http_req_duration` | `p(95)<1000` | ✅ pass |
| `http_req_duration{name:POST /api/auth/login}` | `p(95)<1500` | ✅ pass |
| `http_req_failed` | `rate<0.01` | ✅ pass |
| `checks` | `rate>0.99` | ✅ pass |

## Load over time

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "Virtual users"
    x-axis "elapsed seconds" 0 --> 600
    y-axis "users" 0 --> 184
    line [3, 8, 11, 16, 20, 25, 29, 34, 38, 43, 47, 51, 53, 57, 60, 63, 66, 68, 70, 74, 77, 81, 84, 85, 89, 90, 94, 97, 100, 102, 106, 108, 111, 117, 119, 124, 124, 128, 130, 133, 134, 140, 141, 143, 149, 150, 152, 160, 156, 144, 131, 118, 105, 93, 79, 67, 54, 41, 28, 17, 2]
```

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "Requests per second"
    x-axis "elapsed seconds" 0 --> 600
    y-axis "req/s" 0 --> 349
    line [2, 11.1, 20.1, 28.9, 39.7, 43.3, 53.8, 61, 70.6, 62.6, 83.7, 93.7, 101.6, 102.6, 111.6, 116.9, 124.3, 133.3, 137.9, 142, 147.8, 156.8, 155.1, 164.1, 170.7, 176, 183, 188.3, 193.3, 202.9, 209.6, 210.3, 218.3, 224.3, 219.3, 221.8, 237.6, 249.1, 248.5, 245.6, 269, 269.7, 279.9, 280.5, 289.5, 289.3, 302.7, 300, 297.5, 276.7, 246.8, 211, 192.4, 169.4, 140, 115.2, 90.5, 66.3, 40.5, 15.6, 0.3]
```

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "Response time, 95th percentile"
    x-axis "elapsed seconds" 0 --> 600
    y-axis "ms" 0 --> 1701
    line [180, 196, 272, 171, 173, 172, 163, 148, 168, 1479, 267, 207, 194, 276, 162, 169, 158, 155, 158, 148, 164, 169, 241, 196, 177, 159, 156, 166, 191, 145, 175, 141, 140, 161, 196, 280, 197, 211, 207, 227, 126, 155, 129, 161, 140, 168, 115, 173, 161, 167, 140, 220, 170, 199, 176, 190, 183, 232, 238, 232, 324]
```

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "Failed requests"
    x-axis "elapsed seconds" 0 --> 600
    y-axis "% of requests" 0 --> 2
    line [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
```

<details><summary>Data for these charts (one row per 10 s)</summary>

| Elapsed s | Users | Req/s | p95 ms | Failed % |
| --- | --- | --- | --- | --- |
| 0 | 3 | 2 | 180 | 0 |
| 10 | 8 | 11.1 | 196 | 0 |
| 20 | 11 | 20.1 | 272 | 0 |
| 30 | 16 | 28.9 | 171 | 0 |
| 40 | 20 | 39.7 | 173 | 0 |
| 50 | 25 | 43.3 | 172 | 0 |
| 60 | 29 | 53.8 | 163 | 0 |
| 70 | 34 | 61 | 148 | 0 |
| 80 | 38 | 70.6 | 168 | 0 |
| 90 | 43 | 62.6 | 1479 | 0 |
| 100 | 47 | 83.7 | 267 | 0 |
| 110 | 51 | 93.7 | 207 | 0 |
| 120 | 53 | 101.6 | 194 | 0 |
| 130 | 57 | 102.6 | 276 | 0 |
| 140 | 60 | 111.6 | 162 | 0 |
| 150 | 63 | 116.9 | 169 | 0 |
| 160 | 66 | 124.3 | 158 | 0 |
| 170 | 68 | 133.3 | 155 | 0 |
| 180 | 70 | 137.9 | 158 | 0 |
| 190 | 74 | 142 | 148 | 0 |
| 200 | 77 | 147.8 | 164 | 0 |
| 210 | 81 | 156.8 | 169 | 0 |
| 220 | 84 | 155.1 | 241 | 0 |
| 230 | 85 | 164.1 | 196 | 0 |
| 240 | 89 | 170.7 | 177 | 0 |
| 250 | 90 | 176 | 159 | 0 |
| 260 | 94 | 183 | 156 | 0 |
| 270 | 97 | 188.3 | 166 | 0 |
| 280 | 100 | 193.3 | 191 | 0 |
| 290 | 102 | 202.9 | 145 | 0 |
| 300 | 106 | 209.6 | 175 | 0 |
| 310 | 108 | 210.3 | 141 | 0 |
| 320 | 111 | 218.3 | 140 | 0 |
| 330 | 117 | 224.3 | 161 | 0 |
| 340 | 119 | 219.3 | 196 | 0 |
| 350 | 124 | 221.8 | 280 | 0 |
| 360 | 124 | 237.6 | 197 | 0 |
| 370 | 128 | 249.1 | 211 | 0 |
| 380 | 130 | 248.5 | 207 | 0 |
| 390 | 133 | 245.6 | 227 | 0 |
| 400 | 134 | 269 | 126 | 0 |
| 410 | 140 | 269.7 | 155 | 0 |
| 420 | 141 | 279.9 | 129 | 0 |
| 430 | 143 | 280.5 | 161 | 0 |
| 440 | 149 | 289.5 | 140 | 0 |
| 450 | 150 | 289.3 | 168 | 0 |
| 460 | 152 | 302.7 | 115 | 0 |
| 470 | 160 | 300 | 173 | 0 |
| 480 | 156 | 297.5 | 161 | 0 |
| 490 | 144 | 276.7 | 167 | 0 |
| 500 | 131 | 246.8 | 140 | 0 |
| 510 | 118 | 211 | 220 | 0 |
| 520 | 105 | 192.4 | 170 | 0 |
| 530 | 93 | 169.4 | 199 | 0 |
| 540 | 79 | 140 | 176 | 0 |
| 550 | 67 | 115.2 | 190 | 0 |
| 560 | 54 | 90.5 | 183 | 0 |
| 570 | 41 | 66.3 | 232 | 0 |
| 580 | 28 | 40.5 | 238 | 0 |
| 590 | 17 | 15.6 | 232 | 0 |
| 600 | 2 | 0.3 | 324 | 0 |

</details>

## By endpoint

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta horizontal
    title "Response time p95 by endpoint"
    x-axis ["POST /api/auth/login", "GET /api/claims/{id}", "GET /api/policies/me", "GET /api/claims/queue", "POST /api/auth/logout", "GET /api/auth/me", "GET /api/claims/me", "GET /api/claims?q=", "GET /api/policies/{no}/coverage", "GET /api/claims/{id}/status"]
    y-axis "ms" 0 --> 475
    bar [413, 158, 149, 143, 143, 132, 131, 131, 125, 125]
```

| Endpoint | Requests | Req/s | Avg ms | p50 | p95 | p99 | Max | Failed % | Status codes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `GET /api/policies/me` | 13905 | 23.2 | 87 | 76 | 149 | 251 | 2461 | 0 | 200×13905 |
| `GET /api/policies/{no}/coverage` | 13905 | 23.2 | 82 | 73 | 125 | 215 | 2545 | 0 | 200×13905 |
| `GET /api/claims/me` | 13905 | 23.2 | 82 | 74 | 131 | 211 | 2663 | 0 | 200×13905 |
| `GET /api/claims/{id}` | 13905 | 23.2 | 93 | 81 | 158 | 249 | 2918 | 0 | 200×13905 |
| `GET /api/claims/{id}/status` | 13905 | 23.2 | 80 | 72 | 125 | 211 | 1500 | 0 | 200×13905 |
| `GET /api/claims/queue` | 6582 | 11 | 83 | 71 | 143 | 222 | 2636 | 0 | 200×6582 |
| `GET /api/claims?q=` | 6582 | 11 | 82 | 72 | 131 | 225 | 1664 | 0 | 200×6582 |
| `POST /api/auth/login` | 4790 | 8 | 228 | 198 | 413 | 639 | 2019 | 0 | 200×4790 |
| `GET /api/auth/me` | 4790 | 8 | 81 | 73 | 132 | 207 | 581 | 0 | 200×4790 |
| `POST /api/auth/logout` | 4790 | 8 | 84 | 75 | 143 | 235 | 443 | 0 | 204×4790 |

## Autoscaling (HPA)

Each HPA adds pods when the average CPU goes above 70 % of the pod's CPU request, up to its maximum.
The table shows the fewest and most pods seen during the run.

| HPA | Min pods seen | Max pods seen | Peak CPU | Result |
| --- | --- | --- | --- | --- |
| `approval` | 1 | 1 | 2 % | — |
| `assessment` | 1 | 1 | 11 % | — |
| `claim-intake` | 1 | 2 | 220 % | **scaled out** |
| `eligibility` | 1 | 1 | 9 % | — |
| `frontend` | 1 | 1 | 4 % | — |
| `gateway-simulator` | 1 | 1 | 6 % | — |
| `login` | 1 | 2 | 395 % | **scaled out** |
| `ops-dashboard` | 1 | 1 | 2 % | — |
| `payment` | 1 | 1 | 8 % | — |
| `policy` | 1 | 2 | 181 % | **scaled out** |

#### claim-intake

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "claim-intake: pods running"
    x-axis "elapsed seconds" 0 --> 658
    y-axis "pods" 0 --> 3
    line [1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2]
```

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "claim-intake: CPU % of request"
    x-axis "elapsed seconds" 0 --> 658
    y-axis "CPU %" 0 --> 253
    line [2, 2, 1, 68, 68, 107, 115, 192, 192, 159, 93, 93, 220, 195, 148, 148, 147, 143, 124, 124, 200, 192, 158, 158, 146, 171, 129, 129, 164, 165, 128, 128, 118, 131, 99, 99, 124, 115, 115, 126, 112, 113, 113, 137, 122, 142, 142, 115, 87, 77, 77, 61, 49, 41, 41, 23, 9, 2, 2, 1]
```

#### login

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "login: pods running"
    x-axis "elapsed seconds" 0 --> 658
    y-axis "pods" 0 --> 3
    line [1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2]
```

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "login: CPU % of request"
    x-axis "elapsed seconds" 0 --> 658
    y-axis "CPU %" 0 --> 455
    line [1, 1, 12, 51, 51, 114, 120, 162, 162, 162, 195, 195, 170, 165, 175, 175, 184, 207, 189, 189, 211, 205, 216, 216, 245, 221, 259, 259, 259, 272, 265, 265, 287, 305, 308, 308, 331, 327, 327, 329, 351, 349, 349, 395, 376, 391, 391, 351, 320, 244, 244, 258, 150, 120, 120, 76, 39, 1, 1, 1]
```

#### policy

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "policy: pods running"
    x-axis "elapsed seconds" 0 --> 658
    y-axis "pods" 0 --> 3
    line [1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2]
```

```mermaid
%%{init: {"themeVariables": {"xyChart": {"plotColorPalette": "#2f6fdf"}}}}%%
xychart-beta
    title "policy: CPU % of request"
    x-axis "elapsed seconds" 0 --> 658
    y-axis "CPU %" 0 --> 209
    line [2, 4, 21, 28, 28, 50, 72, 100, 100, 61, 98, 98, 75, 76, 57, 57, 181, 124, 124, 124, 97, 109, 105, 105, 95, 97, 84, 84, 122, 120, 76, 76, 74, 86, 148, 148, 73, 82, 82, 79, 90, 91, 91, 90, 72, 70, 70, 65, 60, 43, 43, 38, 30, 20, 20, 10, 2, 1, 1, 2]
```

---
_Generated by `loadtest/scripts/report.mjs` from the k6 results. The k6 summary (`summary.json`) and the HPA recording (`hpa.csv`) are stored next to this file._
