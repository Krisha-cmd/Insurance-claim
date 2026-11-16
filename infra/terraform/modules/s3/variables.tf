variable "documents_bucket_name" {
  description = "Globally unique name of the claim documents bucket."
  type        = string
}

variable "emr_logs_bucket_name" {
  description = "Globally unique name of the EMR logs bucket."
  type        = string
}

variable "artifacts_bucket_name" {
  description = "Globally unique name of the artifacts bucket (Flink jar, Lambda jar, EMR scripts)."
  type        = string
}

variable "frontend_origins" {
  description = "Browser origins allowed to PUT/GET documents with pre-signed URLs (CORS)."
  type        = list(string)
  default     = ["http://localhost:5173"]
}

variable "upload_events_queue_arn" {
  description = "SQS queue ARN that receives s3:ObjectCreated:* events for keys under the notification prefix."
  type        = string
}

variable "notification_prefix" {
  description = "Only objects under this key prefix trigger document verification."
  type        = string
  default     = "claims/"
}

variable "noncurrent_version_expiration_days" {
  description = "Days after which overwritten/deleted document versions are removed."
  type        = number
  default     = 30
}

variable "emr_log_expiration_days" {
  description = "Days after which EMR logs are deleted."
  type        = number
  default     = 14
}
