# ---------------------------------------------------------------- general
variable "region" {
  description = "AWS region for every resource."
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  description = "Project name; resources are named <project>-<env>-<thing>."
  type        = string
  default     = "claims"
}

variable "env" {
  description = "Environment name; resources are named <project>-<env>-<thing>."
  type        = string
  default     = "dev"
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones (subnets per tier)."
  type        = number
  default     = 2
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway (cheap) instead of one per AZ."
  type        = bool
  default     = true
}

variable "frontend_origins" {
  description = "Browser origins allowed to upload documents to S3 with pre-signed URLs."
  type        = list(string)
  default     = ["http://localhost:5173"]
}

variable "ecr_repositories" {
  description = "One ECR repository is created per name."
  type        = list(string)
  default     = ["login", "claim-intake", "policy", "eligibility", "assessment", "approval", "payment", "ops-dashboard", "frontend"]
}

variable "eks_public_access_cidrs" {
  description = "CIDRs allowed to reach the public Kubernetes API endpoint."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# ---------------------------------------------------------------- sizing
variable "cluster_version" {
  description = "EKS Kubernetes version. Must be in EKS standard support (1.30 is past end of support and can no longer be created)."
  type        = string
  default     = "1.33"
}

variable "node_instance_types" {
  description = "Instance types of the EKS managed node group."
  type        = list(string)
  default     = ["t3.medium"]
}

variable "node_min_size" {
  description = "Minimum EKS worker nodes."
  type        = number
  default     = 2
}

variable "node_desired_size" {
  description = "Desired EKS worker nodes at creation."
  type        = number
  default     = 2
}

variable "node_max_size" {
  description = "Maximum EKS worker nodes."
  type        = number
  default     = 4
}

variable "rds_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "rds_allocated_storage" {
  description = "RDS storage in GiB."
  type        = number
  default     = 20
}

variable "rds_extra_allowed_security_groups" {
  description = "Extra security groups (label => sg-id) allowed to reach PostgreSQL, e.g. a bastion."
  type        = map(string)
  default     = {}
}

variable "msk_broker_instance_type" {
  description = "MSK broker instance type."
  type        = string
  default     = "kafka.t3.small"
}

variable "msk_broker_count" {
  description = "Number of MSK brokers (a multiple of az_count)."
  type        = number
  default     = 2
}

variable "msk_broker_volume_size" {
  description = "EBS per MSK broker in GiB."
  type        = number
  default     = 10
}

variable "emr_release_label" {
  description = "EMR release."
  type        = string
  default     = "emr-7.6.0"
}

variable "emr_primary_instance_type" {
  description = "EMR primary node instance type."
  type        = string
  default     = "m5.xlarge"
}

variable "emr_core_instance_type" {
  description = "EMR core node instance type."
  type        = string
  default     = "m5.xlarge"
}

variable "emr_core_instance_count" {
  description = "Number of EMR core nodes."
  type        = number
  default     = 1
}

variable "emr_idle_timeout_seconds" {
  description = "EMR auto-terminates after this many idle seconds."
  type        = number
  default     = 7200
}

variable "lambda_jar_path" {
  description = "Path to the Document Verification jar, relative to this directory."
  type        = string
  default     = "../../../backend/functions/document-verification/target/document-verification.jar"
}

variable "lambda_handler" {
  description = "Handler of the Document Verification function."
  type        = string
  default     = "com.claims.docverify.Handler::handleRequest"
}

variable "lambda_timeout_seconds" {
  description = "Timeout of the Document Verification function (SQS visibility = 6x)."
  type        = number
  default     = 60
}

# ---------------------------------------------------------------- toggles
variable "enable_rds" {
  description = "Create the PostgreSQL instance."
  type        = bool
  default     = true
}

variable "enable_msk" {
  description = "Create the MSK (Kafka) cluster."
  type        = bool
  default     = true
}

variable "enable_emr" {
  description = "Create the EMR cluster for the Flink job."
  type        = bool
  default     = true
}

variable "enable_lambda" {
  description = "Create the Document Verification Lambda (needs the jar to be built)."
  type        = bool
  default     = true
}

variable "enable_dynamodb" {
  description = "Create the DynamoDB analytics table (and the ops-dashboard IRSA role)."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------- GitOps / CI
variable "enable_github_oidc" {
  description = "Create a GitHub Actions OIDC provider and an ECR push role for CI."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "GitHub repository (owner/name) allowed to assume the CI role."
  type        = string
  default     = ""
}
