# Backend

Java 21 · Spring Boot 3.3 · Maven multi-module. One module per service. Active in the parent
[`pom.xml`](pom.xml): `proto`, `common`, `participant`, `services/login`, `services/approval` and
`services/claim-intake`, `services/policy`, `functions/document-verification`, `services/payment` and
`services/gateway-simulator`; the others are commented out and get switched on one at a time as they are built.

```
backend/
├── pom.xml                 parent: Spring Boot parent, Java 21, Jib + git-commit-id plugin config
├── docker-compose.yml      local Postgres 16 (database/user/password: login/login/login)
├── proto/                  gRPC contracts (.proto) → generated Java stubs
├── common/                 ApiError, GlobalExceptionHandler base, ClaimEvent envelope + EventPublishers, JSON logging
├── participant/            3PC participant base (ThreePhaseParticipant, ParticipantTx, gRPC adapter);
│                           its test-jar holds ThreePhaseParticipantContract, the cases every participant must pass
├── services/login/         login service: /api/auth/*, /.well-known/jwks.json
├── services/approval/      decisions + 3PC settlement coordinator (see its README)
├── services/claim-intake/  claims, uploads, verification results, checks, queue, 3PC participant (see its README)
├── services/policy/        policies, members, coverage; 3PC participant reserving the sum insured (see its README)
├── functions/document-verification/  AWS Lambda: S3 upload → checks → VERIFIED/REJECTED result (see its README)
├── services/payment/       3PC participant paying through the gateway: authorize / capture / void (see its README)
└── services/gateway-simulator/  in-memory fake payment gateway with chaos modes (see its README)
```

## Prerequisites

- JDK 21 or newer (the code targets Java 21)
- Maven 3.9+
- Docker (for `docker compose` and for the Testcontainers integration tests)

## Build and test one module

Run from `backend/`. `-am` also builds the modules it depends on (`common`).

```bash
mvn -pl services/login -am package          # compile, unit + integration tests, build target/login.jar
mvn -pl services/login -am verify           # same, plus the verify phase
mvn -pl services/login -am package -DskipTests
```

The integration test starts a throw-away `postgres:16-alpine` container, so Docker must be running.

## Run locally

```bash
docker compose up -d                                              # Postgres on localhost:5432
mvn -pl services/login -am install -DskipTests                    # once, so `common` is in ~/.m2
mvn -pl services/login spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway creates the schema and seeds two users on first start. The password for both is `password`.

| Username    | Role         | Name        |
| ----------- | ------------ | ----------- |
| `patient1`  | POLICYHOLDER | Asha Verma  |
| `adjuster1` | ADJUSTER     | Rohan Mehta |

The fixed user and policyholder UUIDs are listed in
[`V2__seed_users.sql`](services/login/src/main/resources/db/migration/V2__seed_users.sql) so other services can refer to them.

```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"patient1","password":"password"}'
curl -s localhost:8080/api/auth/me -H "Authorization: Bearer <token>"
```

In the `local` profile, if `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` are not set, the service generates
a key pair in memory and logs a warning. Tokens then stop working after a restart. In any other
profile, missing keys stop the service from starting.

### Configuration

Every setting comes from an environment variable. The defaults suit local development.

| Variable | Default | On EKS comes from |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` | Deployment (`aws`) |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/login` | `login-db` Secret |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `login` / `login` | `login-db` Secret |
| `JWT_PRIVATE_KEY` | *(generated in `local`)* | `jwt-keys` Secret, `private.pem` (PKCS#8) |
| `JWT_PUBLIC_KEY` | *(generated in `local`)* | `jwt-keys` Secret, `public.pem` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Deployment (comma-separated) |
| `SERVER_PORT` | `8080` | |

Logs are plain text in `local` and one JSON object per line in `aws`.

## Build and push an image

Images are built with [Jib](https://github.com/GoogleContainerTools/jib), so no Dockerfile or
Docker daemon is needed for `jib:build`. The base image is `eclipse-temurin:21-jre`, the platform
is linux/amd64 (matching the EKS nodes), and the container runs as a non-root user.

- `-Dimage=<registry>/<repository>` sets the image name. The default is `claims/<artifactId>`.
- The image gets two tags, `latest` and the short Git commit id (`git rev-parse --short=7 HEAD`).
  Pass `-Dtag=<tag>` to use a tag of your own instead of the commit id.

```bash
# Into the local Docker daemon, for trying the image out
mvn -pl services/login -am package jib:dockerBuild -DskipTests -Dimage=claims/login

# To ECR (the URL is the `ecr_repository_urls["login"]` output of the infra stack)
ECR=$(terraform -chdir=../infra/terraform/infra output -json ecr_repository_urls | jq -r .login)
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "${ECR%%/*}"
mvn -pl services/login -am compile jib:build -Dimage="$ECR" -Dtag="$(git rev-parse --short=7 HEAD)"
```

`jib:build` reads the registry login from Docker's credential store, which is filled by the
`docker login` above. `common` sets `jib.skip`, so `-am` builds an image for the service only.
