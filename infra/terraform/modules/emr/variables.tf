variable "name" {
  description = "Name prefix; the cluster is called <name>-flink."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the cluster."
  type        = string
}

variable "subnet_id" {
  description = "Single private subnet the cluster runs in (EMR instance groups live in one AZ)."
  type        = string
}

variable "release_label" {
  description = "EMR release."
  type        = string
  default     = "emr-7.2.0"
}

variable "applications" {
  description = "EMR applications to install."
  type        = list(string)
  default     = ["Flink", "Hadoop"]
}

variable "primary_instance_type" {
  description = "Instance type of the primary (master) node."
  type        = string
  default     = "m5.xlarge"
}

variable "core_instance_type" {
  description = "Instance type of the core nodes."
  type        = string
  default     = "m5.xlarge"
}

variable "core_instance_count" {
  description = "Number of core nodes."
  type        = number
  default     = 1
}

variable "core_ebs_size" {
  description = "EBS volume per core node, in GiB."
  type        = number
  default     = 32
}

variable "idle_timeout_seconds" {
  description = "Terminate the cluster after this many idle seconds (no running steps or YARN apps)."
  type        = number
  default     = 7200
}

variable "logs_bucket_name" {
  description = "Bucket receiving EMR logs (s3://<bucket>/)."
  type        = string
}

variable "logs_bucket_arn" {
  description = "ARN of the EMR logs bucket."
  type        = string
}

variable "artifacts_bucket_name" {
  description = "Bucket holding the Flink job jar and the bootstrap script."
  type        = string
}

variable "artifacts_bucket_arn" {
  description = "ARN of the artifacts bucket."
  type        = string
}

variable "enable_dynamodb" {
  description = "Grant the instance profile write access to the analytics table."
  type        = bool
  default     = true
}

variable "analytics_table_arn" {
  description = "ARN of the analytics DynamoDB table (ignored when enable_dynamodb = false)."
  type        = string
  default     = null
}

variable "msk_cluster_name" {
  description = "Name of the MSK cluster the Flink job connects to (used to scope kafka-cluster IAM permissions)."
  type        = string
}

variable "log_retention_days" {
  description = "CloudWatch retention of the Flink/YARN log group."
  type        = number
  default     = 7
}
