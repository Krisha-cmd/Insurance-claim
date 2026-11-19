#!/usr/bin/env bash
# Stops the services started by run-all.sh.
cd "$(dirname "$0")/logs" 2>/dev/null || exit 0
for f in *.pid; do
  [[ -f "$f" ]] || continue
  kill "$(cat "$f")" 2>/dev/null && echo "stopped ${f%.pid}"
  rm -f "$f"
done
