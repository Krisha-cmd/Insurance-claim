#!/usr/bin/env bash
# Starts every backend service on this machine, each on its own port, wired to each other.
# Run from backend/:   ./local/run-all.sh        (add --build to rebuild the jars first)
# Stop them with:      ./local/stop-all.sh       Logs: backend/local/logs/<service>.log
#
# Ports (the frontend's Vite proxy in frontend/vite.config.js uses the same table):
#   service             HTTP   gRPC
#   login               8081   -
#   policy              8082   9092
#   claim-intake        8083   9093
#   eligibility         8084   9094
#   assessment          8085   9095
#   approval            8086   -      (only calls gRPC)
#   payment             8087   9097
#   gateway-simulator   8088   -
#   ops-dashboard       8089   -
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p local/logs
./local/stop-all.sh   # a running jar is locked on Windows and can't be rebuilt

if [[ "${1:-}" == "--build" || ! -f services/login/target/login.jar ]]; then
  echo "Building the jars (tests skipped)..."
  mvn -B -q -DskipTests package
fi

JWKS=http://localhost:8081/.well-known/jwks.json
COMMON=(--spring.profiles.active=local)

start() {  # start <service> <env...>
  local svc=$1; shift
  echo "starting $svc"
  env "$@" nohup java -jar "services/$svc/target/$svc.jar" "${COMMON[@]}" > "local/logs/$svc.log" 2>&1 &
  echo $! > "local/logs/$svc.pid"
}

start login             SERVER_PORT=8081 CORS_ALLOWED_ORIGINS=http://localhost:5173
start policy            SERVER_PORT=8082 GRPC_PORT=9092 JWT_JWKS_URI=$JWKS GRPC_CLAIM_TARGET=localhost:9093
start claim-intake      SERVER_PORT=8083 GRPC_PORT=9093 JWT_JWKS_URI=$JWKS \
                        GRPC_POLICY_TARGET=localhost:9092 GRPC_ELIGIBILITY_TARGET=localhost:9094 GRPC_ASSESSMENT_TARGET=localhost:9095
start eligibility       SERVER_PORT=8084 GRPC_PORT=9094 JWT_JWKS_URI=$JWKS GRPC_POLICY_TARGET=localhost:9092 GRPC_CLAIM_TARGET=localhost:9093
start assessment        SERVER_PORT=8085 GRPC_PORT=9095 JWT_JWKS_URI=$JWKS GRPC_POLICY_TARGET=localhost:9092 GRPC_CLAIM_TARGET=localhost:9093
start approval          SERVER_PORT=8086 JWT_JWKS_URI=$JWKS GRPC_INTAKE_TARGET=localhost:9093 GRPC_POLICY_TARGET=localhost:9092 \
                        GRPC_PAYMENT_TARGET=localhost:9097 GRPC_ASSESSMENT_TARGET=localhost:9095
start payment           SERVER_PORT=8087 GRPC_PORT=9097 GRPC_CLAIM_TARGET=localhost:9093 GATEWAY_BASE_URL=http://localhost:8088
start gateway-simulator SERVER_PORT=8088
start ops-dashboard     SERVER_PORT=8089 JWT_JWKS_URI=$JWKS GRPC_CLAIM_TARGET=localhost:9093 \
                        APPROVAL_URL=http://localhost:8086 PAYMENT_URL=http://localhost:8087

echo "Waiting for every service to report UP..."
for port in 8081 8082 8083 8084 8085 8086 8087 8088 8089; do
  for _ in $(seq 1 90); do
    if curl -sf "http://localhost:$port/actuator/health/readiness" > /dev/null 2>&1 \
       || curl -sf "http://localhost:$port/actuator/health" > /dev/null 2>&1; then
      echo "  :$port UP"; continue 2
    fi
    sleep 2
  done
  echo "  :$port did not come up; see local/logs/" >&2
done
echo "Backend running. Start the frontend with: cd ../frontend && npm run dev:backend"
