variable "name" {
  description = "Name prefix; queues are called <name>-<queue>."
  type        = string
}

variable "documents_bucket_arn" {
  description = "ARN of the documents bucket that may send S3 event notifications to doc-upload-events."
  type        = string
}

variable "lambda_timeout_seconds" {
  description = "Timeout of the consuming Lambda. Visibility timeout is set to 6x this, as AWS recommends."
  type        = number
  default     = 60
}

variable "max_receive_count" {
  description = "Deliveries before a message is moved to the dead-letter queue."
  type        = number
  default     = 3
}

variable "message_retention_seconds" {
  description = "How long unconsumed messages are kept (default 4 days)."
  type        = number
  default     = 345600
}
