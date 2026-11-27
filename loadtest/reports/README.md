# Load test reports

Every run of `loadtest/run.sh` adds a folder here. Newest first.

| Report | Profile | Target | Requests | p95 | Failed | HPAs scaled out | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [2026-11-27-132326-stress](2026-11-27-132326-stress/report.md) | `stress` (read-only) | `http://k8s-claims-8285bb1f19-797935702.ap-south-1.elb.amazonaws.com` | 97,059 | 188 ms | 0 % | 3 | ✅ all thresholds passed |
| [2026-11-19-104836-load](2026-11-19-104836-load/report.md) | `load` | `http://127.0.0.1:5174` | 44,512 | 84 ms | 0 % | — | ✅ all thresholds passed |
