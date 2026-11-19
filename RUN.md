# How to run the Claims Platform

## What is this?

Imagine a hospital bill you want your insurance company to pay. You fill in a form (**a claim**) and attach
your papers. A computer checks the papers, checks that your insurance covers you, works out how much to pay,
and a person (**the adjuster**) says yes or no. If it's yes, the money is paid. Every step is written down so
nobody can cheat.

This project is that whole system. It's made of many small programs, each doing one job, that talk to each
other.

## The pieces, and why each one is used

| Piece | What it is, simply | Why we use it |
| --- | --- | --- |
| **Frontend** (React + Vite) | The website you click on | One app for customers and adjusters. Vite makes it start fast while you build it |
| **Microservices** (Java + Spring Boot) | Nine small programs: login, policy, claim intake, eligibility, assessment, approval, payment, gateway simulator, ops dashboard | Each does one job, so you can fix or scale one without touching the others |
| **REST** (HTTP + JSON) | How the website talks to the programs: "GET me my claims" | Every browser speaks it |
| **gRPC** | How the programs talk to *each other* | Faster than REST and strictly typed, so they can't misunderstand each other. The contracts are in `backend/proto` |
| **PostgreSQL** | A filing cabinet for each program | Each program owns its own cabinet ("database per service"), so none can mess up another's data |
| **3PC** (three-phase commit) | A "ready? set? go!" between approval, claim, policy and payment | Paying a claim must happen everywhere or nowhere, even if a program crashes halfway |
| **Kafka** | A noticeboard where every program posts what just happened | The audit job reads it to keep a complete history |
| **Flink** (on EMR) | A robot that reads the noticeboard nonstop | Builds the audit trail, live statistics and fraud alerts |
| **DynamoDB** | A very fast lookup table | The ops dashboard reads statistics from it instantly |
| **S3 + SQS + Lambda** | A locker for uploaded papers, a queue, and a tiny program that wakes up to check each paper | Papers are checked only when one arrives, so you pay nothing while it sleeps |
| **Docker** | A lunchbox that holds a program and everything it needs | It runs the same on every computer |
| **Kubernetes** (EKS) | A manager that keeps all the lunchboxes running | It restarts crashed programs and adds copies when busy (HPA) |
| **Terraform** | A recipe that builds everything on AWS | Nothing is clicked by hand, so it can be rebuilt or deleted with one command |
| **GitOps** (Argo CD) + **CI** (GitHub Actions) | CI builds the lunchboxes; Argo CD copies whatever is in Git onto Kubernetes | Git is the single source of truth. To change or undo something, change Git |
| **Ops dashboard** | The adjuster's control room: audit trail, charts, fraud flags, chaos buttons | To watch the system and break it on purpose to show the 3PC recovering |

## What you need installed

Java 21, Maven 3.9+, Node 20+, Docker, and Git Bash on Windows. For AWS you also need the AWS CLI v2,
Terraform 1.6+ and kubectl.

Every user's password is `password`. Sign in as **`patient1`** (customer) or **`adjuster1`** (adjuster).

---

## Part A: on your own computer

### A1. Just the website (fake backend, 1 minute)

The website ships with a pretend backend inside the browser.

```bash
cd frontend
npm install
npm run dev                     # open http://localhost:5173
```

### A2. Website + the real backend

```bash
# 1. the database (one Postgres, one database per service)
cd backend
docker compose up -d
docker exec -i claims-postgres psql -U login -d login < local/create-databases.sql

# 2. all nine services, each on its own port (first run builds the jars)
./local/run-all.sh              # wait for nine "UP" lines; logs are in backend/local/logs

# 3. the website, using the real backend
cd ../frontend
npm run dev:backend             # open http://localhost:5173
```

`npm run dev:backend` turns the fake backend off. The Vite dev server then sends each `/api/...` path to the
right service, just as the AWS load balancer does. All routes are listed in `frontend/vite.config.js`.

**Works locally:** sign in, policies, filing claims, the adjuster's queue and search, and the chaos buttons.
**Needs AWS:** uploading documents (S3, SQS and the Lambda), and the audit trail and charts (Flink and
DynamoDB). Locally these answer *"not available"* (HTTP 503).

```bash
./local/stop-all.sh             # from backend/: stop the services
docker compose down             # stop the database (add -v to delete its data too)
```

### A3. Run the tests

Run these from `backend/`. Docker must be running.

```bash
mvn verify                                      # every service: unit + integration tests
mvn -f analytics/audit-flink/pom.xml package    # the Flink job and its tests
cd ../frontend && npm run build                 # the website compiles
```

---

## Part B: on AWS (the real thing)

> ⚠️ This costs about **$1 per hour**. Run step 9 when you're done.

Run every command from the repository root unless it says `cd`.

```bash
# 1. log in to AWS
aws configure                    # access key, secret, region ap-south-1
aws sts get-caller-identity      # prints your account ID; you need it below

# 2. a place for Terraform to remember what it built (once per account)
cd infra/terraform/bootstrap && terraform init && terraform apply && cd -

# 3. build the document-checking Lambda (Terraform uploads this jar)
cd backend && mvn -pl functions/document-verification -am package && cd -

# 4. the cloud: network, Kubernetes, databases, Kafka, EMR, S3, SQS, Lambda, DynamoDB, ECR (~25 min)
cd infra/terraform/infra
terraform init -backend-config="bucket=claims-dev-tfstate-<account_id>" \
  -backend-config="region=ap-south-1" -backend-config="dynamodb_table=claims-dev-tf-locks"
terraform apply -var-file=dev.tfvars
aws eks update-kubeconfig --name claims-dev --region ap-south-1
cd -

# 5. inside Kubernetes: load balancer controller, monitoring, database users, secrets, and Argo CD (GitOps)
cd infra/terraform/platform
echo 'gitops_repo_url = "https://github.com/Krisha-cmd/Insurance-claim.git"' > terraform.tfvars
terraform init -backend-config="bucket=claims-dev-tfstate-<account_id>" \
  -backend-config="region=ap-south-1" -backend-config="dynamodb_table=claims-dev-tf-locks"
terraform apply
cd -
```

**6. Put the programs on Kubernetes (GitOps).** You never run `kubectl apply` for the services.
- **One-time setup:** set `enable_github_oidc = true` and `github_repository = "Krisha-cmd/Insurance-claim"`
  in `infra/terraform/infra/dev.tfvars`, then run `terraform apply` there again. In GitHub, under
  *Settings → Environments → dev*, add `AWS_ROLE_ARN` (the infra output `irsa_role_arns.github_actions`)
  and `AWS_REGION`.
- **Then push to `main`.** The **deliver** workflow tests, builds the Docker images, pushes them to ECR and
  commits the new image tags into `k8s/overlays/dev`.
- **Argo CD** notices that commit and deploys it, usually within 3 minutes.

```bash
kubectl -n argocd get applications     # claims-root and claims-dev: Synced / Healthy
kubectl -n claims get pods             # every service Running
```

```bash
# 7. start the audit robot (Flink) on EMR
cd backend && mvn -f analytics/audit-flink/pom.xml package && cd -
cd infra/terraform/infra
ARTIFACTS=$(terraform output -raw artifacts_bucket); CLUSTER_ID=$(terraform output -raw emr_cluster_id)
BROKERS=$(terraform output -raw msk_bootstrap_brokers); TABLE=$(terraform output -raw analytics_table_name)
aws s3 cp ../../../backend/analytics/audit-flink/target/audit-flink.jar s3://$ARTIFACTS/flink/audit-flink.jar
aws emr add-steps --cluster-id "$CLUSTER_ID" --steps \
  "Type=CUSTOM_JAR,Name=flink-session,ActionOnFailure=CONTINUE,Jar=command-runner.jar,Args=[flink-yarn-session,-d]" \
  "Type=CUSTOM_JAR,Name=audit-flink,ActionOnFailure=CONTINUE,Jar=command-runner.jar,Args=[bash,-c,aws s3 cp s3://$ARTIFACTS/flink/audit-flink.jar /home/hadoop/audit-flink.jar && flink run -d /home/hadoop/audit-flink.jar --bootstrap $BROKERS --events-topic claim-events --results-topic claim-analytics --table $TABLE --region ap-south-1 --checkpoint-dir s3://$ARTIFACTS/flink/checkpoints]"
cd -

# 8. open it
kubectl -n claims get ingress          # the ADDRESS column is the website: http://<address>
kubectl -n argocd port-forward svc/argocd-server 8080:80                     # Argo CD at http://localhost:8080
kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80   # Grafana at http://localhost:3000

# 9. delete everything (stops the bill); full safe order in infra/README.md
kubectl delete ingress --all -A
cd infra/terraform/platform && terraform destroy && cd ../infra && terraform destroy -var-file=dev.tfvars
```

### Changing something later

1. Open a pull request. The **validate** workflow checks the code, the Kubernetes files and Terraform.
2. Merge it. The **deliver** workflow builds the new images and commits the new tags.
3. Argo CD deploys it.

**To undo a release**, `git revert` the `deploy(dev): …` commit and push.

The Lambda and the Flink job aren't on Kubernetes:
- **Lambda:** rebuild the jar, then run `terraform apply` in `infra/terraform/infra`.
- **Flink job:** repeat step 7.

## Where to read more

| Topic | File |
| --- | --- |
| Design, diagrams, why | `docs/design-document.md` (and the PDF next to it) |
| AWS and Terraform, step by step | `infra/README.md` |
| GitOps, CI, rollback | `gitops/README.md` |
| One service | `backend/services/<name>/README.md` |
| Lambda / Flink | `backend/functions/document-verification/README.md`, `backend/analytics/audit-flink/README.md` |
| Website | `frontend/README.md` |
