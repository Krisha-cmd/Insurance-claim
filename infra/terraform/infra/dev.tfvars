# Values for the dev environment. Every value here equals the default in
# variables.tf, so this file documents the knobs; change what you need.

# ---------------------------------------------------------------- general
region                            = "ap-south-1"              # AWS region for every resource
project                           = "claims"                  # resources are named <project>-<env>-<thing>
env                               = "dev"                     # environment name
vpc_cidr                          = "10.20.0.0/16"            # VPC address range
az_count                          = 2                         # availability zones (subnets per tier)
single_nat_gateway                = true                      # one NAT gateway (cheap) instead of one per AZ
frontend_origins                  = ["http://localhost:5173"] # origins allowed to upload documents (S3 CORS)
eks_public_access_cidrs           = ["0.0.0.0/0"]             # who may reach the Kubernetes API; narrow to your IP/32
rds_extra_allowed_security_groups = {}                        # extra SGs allowed into PostgreSQL, e.g. { bastion = "sg-..." }
ecr_repositories = [                                          # one ECR repository per image
  "login", "claim-intake", "policy", "eligibility", "assessment",
  "approval", "payment", "ops-dashboard", "frontend",
]

# ---------------------------------------------------------------- sizing
cluster_version           = "1.33"                                                                              # EKS Kubernetes version (must be in standard support)
node_instance_types       = ["t3.medium"]                                                                       # EKS worker instance types
node_min_size             = 2                                                                                   # EKS minimum nodes
node_desired_size         = 2                                                                                   # EKS nodes at creation
node_max_size             = 4                                                                                   # EKS maximum nodes
rds_instance_class        = "db.t4g.micro"                                                                      # PostgreSQL instance class
rds_allocated_storage     = 20                                                                                  # PostgreSQL storage, GiB
msk_broker_instance_type  = "kafka.t3.small"                                                                    # Kafka broker type
msk_broker_count          = 2                                                                                   # Kafka brokers (multiple of az_count)
msk_broker_volume_size    = 10                                                                                  # Kafka EBS per broker, GiB
emr_release_label         = "emr-7.2.0"                                                                         # EMR release (ships Flink 1.18)
emr_primary_instance_type = "m5.xlarge"                                                                         # EMR primary node
emr_core_instance_type    = "m5.xlarge"                                                                         # EMR core nodes
emr_core_instance_count   = 1                                                                                   # number of EMR core nodes
emr_idle_timeout_seconds  = 7200                                                                                # EMR shuts down after 2 h idle
lambda_timeout_seconds    = 60                                                                                  # Lambda timeout (SQS visibility = 6x)
lambda_jar_path           = "../../../backend/functions/document-verification/target/document-verification.jar" # built Lambda jar
lambda_handler            = "com.claims.docverify.Handler::handleRequest"                                       # Lambda entry point

# ---------------------------------------------------------------- toggles
enable_rds      = true # PostgreSQL (needed by every service)
enable_msk      = true # Kafka; slowest part of the apply (~20 min)
enable_emr      = true # Flink on EMR; most expensive part
enable_lambda   = true # Document Verification; set false until the jar is built
enable_dynamodb = true # analytics table + ops-dashboard IRSA role

# ---------------------------------------------------------------- GitOps / CI
enable_github_oidc = false # create GitHub Actions OIDC + ECR push role
github_repository  = ""    # "owner/repo" allowed to assume that role
