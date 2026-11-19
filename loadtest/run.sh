#!/usr/bin/env bash
# Runs the k6 load test, records the HPAs while it runs (when kubectl points at a cluster), and writes
# loadtest/reports/<date>-<profile>/report.md with graphs.
#
#   BASE_URL=http://<ingress-address> PROFILE=load ./loadtest/run.sh
#
#   BASE_URL   the website address; every /api/... path is sent there      (default http://localhost:5173)
#   PROFILE    smoke (1 min) | load (10 min, 40 users) | stress (10 min, up to 150 users)   (default smoke)
#   WRITES     false = read-only, files no claims                           (default true)
#   WATCH_HPA  auto | true | false                                          (default auto: true when kubectl can see the namespace)
#   NAMESPACE  where the HPAs live                                          (default claims)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:5173}"
PROFILE="${PROFILE:-smoke}"
WRITES="${WRITES:-true}"
WATCH_HPA="${WATCH_HPA:-auto}"
NAMESPACE="${NAMESPACE:-claims}"

command -v k6 >/dev/null || { echo "k6 is not installed: https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2; exit 1; }
command -v node >/dev/null || { echo "Node.js is needed for the report" >&2; exit 1; }

name="$(date +%Y-%m-%d-%H%M%S)-$PROFILE"
results="$here/results/$name"     # raw samples (large, not committed)
report="$here/reports/$name"      # the report (committed)
mkdir -p "$results"

if [ "$WATCH_HPA" = auto ]; then
  if command -v kubectl >/dev/null && kubectl -n "$NAMESPACE" get hpa --request-timeout=5s >/dev/null 2>&1; then WATCH_HPA=true; else WATCH_HPA=false; fi
fi
watcher=""
if [ "$WATCH_HPA" = true ]; then
  echo "Recording the HPAs in namespace $NAMESPACE every 10 s"
  bash "$here/scripts/watch-hpa.sh" "$results/hpa.csv" "$NAMESPACE" 10 &
  watcher=$!
  trap '[ -n "$watcher" ] && kill "$watcher" 2>/dev/null || true' EXIT
fi

echo "Load test: profile $PROFILE against $BASE_URL"
set +e
k6 run --quiet --out "csv=$results/raw.csv" \
  -e BASE_URL="$BASE_URL" -e PROFILE="$PROFILE" -e WRITES="$WRITES" -e RESULTS_DIR="$results" \
  "$here/k6/claims-load.js"
k6_status=$?
set -e

if [ -n "$watcher" ]; then
  # keep watching for 60 s so the report shows the HPAs reacting after the peak
  echo "Watching the HPAs for 60 s more"; sleep 60
  kill "$watcher" 2>/dev/null || true; watcher=""
fi

node "$here/scripts/report.mjs" "$results" "$report"
node "$here/scripts/index.mjs" "$here/reports"
[ "${KEEP_RAW:-false}" = true ] || rm -f "$results/raw.csv"

# k6 exits 99 when a threshold failed: the report is still written, but the run counts as failed
exit $k6_status
