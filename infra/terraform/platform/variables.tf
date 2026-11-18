# ---------------------------------------------------------------- general
variable "region" {
  description = "AWS region (must match the infra stack)."
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  description = "Project name (must match the infra stack)."
  type        = string
  default     = "claims"
}

variable "env" {
  description = "Environment name (must match the infra stack)."
  type        = string
  default     = "dev"
}

variable "state_bucket" {
  description = "Bucket holding the infra stack's state. Empty = the bootstrap default <project>-<env>-tfstate-<account_id>."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------- GitOps
variable "gitops_repo_url" {
  description = "Git repository ArgoCD syncs from (HTTPS URL). Empty = install ArgoCD without the root app."
  type        = string
  default     = ""
}

variable "gitops_revision" {
  description = "Branch, tag or commit the root app tracks."
  type        = string
  default     = "main"
}

variable "gitops_path" {
  description = "Directory in the repo holding the per-environment Application manifests (app of apps)."
  type        = string
  default     = "gitops/apps"
}

variable "gitops_repo_private" {
  description = "Set true for a private repo; then supply gitops_repo_token."
  type        = bool
  default     = false
}

variable "gitops_repo_username" {
  description = "Username sent with the token for a private repo (GitHub accepts any non-empty value)."
  type        = string
  default     = "git"
}

variable "gitops_repo_token" {
  description = "Personal access token with read access to the GitOps repo. Pass it with TF_VAR_gitops_repo_token, never in a file."
  type        = string
  default     = ""
  sensitive   = true
}

# ---------------------------------------------------------------- exposure
variable "expose_argocd" {
  description = "Publish the ArgoCD UI through an internet-facing ALB (default: port-forward only)."
  type        = bool
  default     = false
}

variable "expose_grafana" {
  description = "Publish Grafana through an internet-facing ALB (default: port-forward only)."
  type        = bool
  default     = false
}

variable "ingress_allowed_cidrs" {
  description = "Source CIDRs allowed through the ALBs created by expose_argocd / expose_grafana."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# ---------------------------------------------------------------- data / messaging
variable "db_services" {
  description = "One PostgreSQL database + owner user + <name>-db Secret is created per entry."
  type        = list(string)
  default     = ["login", "policy", "claims", "assessment", "approval", "payment"]
}

variable "kafka_topics" {
  description = "Kafka topics created by the kafka-topics Job (when MSK is enabled)."
  type        = list(string)
  default     = ["claim-events", "claim-analytics", "doc-verified"]
}

variable "kafka_topic_partitions" {
  description = "Partitions per Kafka topic."
  type        = number
  default     = 3
}

variable "kafka_image" {
  description = "Image with kafka-topics.sh. The official apache/kafka image is used because the free bitnami/kafka tags were withdrawn from Docker Hub in 2025."
  type        = string
  default     = "apache/kafka:3.7.0"
}

variable "postgres_image" {
  description = "Image used by the db-init Job (needs psql)."
  type        = string
  default     = "postgres:16-alpine"
}

# ---------------------------------------------------------------- observability
variable "prometheus_retention" {
  description = "How long Prometheus keeps metrics."
  type        = string
  default     = "7d"
}

variable "prometheus_storage_size" {
  description = "Size of the Prometheus PVC."
  type        = string
  default     = "10Gi"
}

variable "loki_storage_size" {
  description = "Size of the Loki PVC."
  type        = string
  default     = "10Gi"
}
