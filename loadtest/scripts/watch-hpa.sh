#!/usr/bin/env bash
# Records every HPA in the namespace every INTERVAL seconds, as CSV, until it is stopped.
#   watch-hpa.sh <out.csv> [namespace] [interval]
# Columns: epoch seconds, HPA name, current replicas, desired replicas, average CPU % of the request.
set -u
out="$1"; ns="${2:-claims}"; interval="${3:-10}"
echo "ts,hpa,current,desired,cpu" > "$out"
while true; do
  ts=$(date +%s)
  kubectl -n "$ns" get hpa --no-headers \
    -o custom-columns='N:.metadata.name,C:.status.currentReplicas,D:.status.desiredReplicas,U:.status.currentMetrics[0].resource.current.averageUtilization' \
    2>/dev/null | awk -v ts="$ts" '{ printf "%s,%s,%s,%s,%s\n", ts, $1, $2, $3, ($4 == "<none>" ? "" : $4) }' >> "$out"
  sleep "$interval"
done
