variable "function_name" {
  description = "Name of the Lambda function."
  type        = string
}

variable "role_arn" {
  description = "Execution role ARN (created by the iam module)."
  type        = string
}

variable "jar_path" {
  description = "Path to the shaded Lambda jar, relative to the directory you run terraform in."
  type        = string
  default     = "../../../backend/functions/document-verification/target/document-verification.jar"
}

variable "handler" {
  description = "Fully qualified handler (class::method)."
  type        = string
  default     = "com.claims.docverify.Handler::handleRequest"
}

variable "runtime" {
  description = "Lambda runtime."
  type        = string
  default     = "java21"
}

variable "memory_mb" {
  description = "Memory in MB (CPU scales with it)."
  type        = number
  default     = 512
}

variable "timeout_seconds" {
  description = "Function timeout in seconds. Keep the SQS visibility timeout at 6x this."
  type        = number
  default     = 60
}

variable "upload_events_queue_arn" {
  description = "SQS queue (doc-upload-events) that triggers the function."
  type        = string
}

variable "results_queue_url" {
  description = "SQS queue URL the function publishes verification results to."
  type        = string
}

variable "documents_bucket_name" {
  description = "Bucket with the uploaded claim documents."
  type        = string
}

variable "allowed_types" {
  description = "Comma-separated MIME types the verifier accepts."
  type        = string
  default     = "application/pdf,image/jpeg,image/png"
}

variable "max_size_mb" {
  description = "Largest document size the verifier accepts, in MB."
  type        = number
  default     = 10
}

variable "batch_size" {
  description = "Messages per Lambda invocation."
  type        = number
  default     = 5
}

variable "log_retention_days" {
  description = "CloudWatch retention of the function logs."
  type        = number
  default     = 7
}
