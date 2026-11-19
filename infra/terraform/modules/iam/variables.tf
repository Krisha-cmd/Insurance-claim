variable "name" {
  description = "Name prefix for role names (e.g. claims-dev)."
  type        = string
}

variable "oidc_provider_arn" {
  description = "ARN of the EKS cluster's IAM OIDC provider."
  type        = string
}

variable "oidc_provider_url" {
  description = "OIDC issuer without https:// (e.g. oidc.eks.ap-south-1.amazonaws.com/id/ABC)."
  type        = string
}

variable "app_namespace" {
  description = "Kubernetes namespace of the application service accounts."
  type        = string
  default     = "claims"
}

variable "observability_namespace" {
  description = "Kubernetes namespace of Grafana."
  type        = string
  default     = "observability"
}

variable "documents_bucket_arn" {
  description = "ARN of the claim documents bucket."
  type        = string
}

variable "documents_prefix" {
  description = "Key prefix of claim documents inside the bucket."
  type        = string
  default     = "claims/"
}

variable "upload_events_queue_arn" {
  description = "ARN of doc-upload-events (consumed by the Lambda)."
  type        = string
}

variable "results_queue_arn" {
  description = "ARN of doc-verification-results (Lambda sends, Claim Intake consumes)."
  type        = string
}

variable "enable_dynamodb" {
  description = "Create the ops-dashboard role with DynamoDB access."
  type        = bool
  default     = true
}

variable "analytics_table_arn" {
  description = "ARN of the analytics table (ignored when enable_dynamodb = false)."
  type        = string
  default     = null
}

variable "enable_lambda" {
  description = "Create the Lambda execution role."
  type        = bool
  default     = true
}

variable "lambda_function_name" {
  description = "Name of the Document Verification function (scopes its log permissions)."
  type        = string
}

variable "enable_github_oidc" {
  description = "Create a GitHub Actions OIDC provider and a role that can push to ECR."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "GitHub repository allowed to assume the CI role, as owner/name. Required when enable_github_oidc = true."
  type        = string
  default     = "Krisha-cmd/Insurance-claim"
}

variable "ecr_repository_arns" {
  description = "ECR repository ARNs the CI role may push to."
  type        = list(string)
  default     = []
}
