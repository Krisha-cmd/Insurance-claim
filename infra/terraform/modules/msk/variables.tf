variable "name" {
  description = "Name prefix; the cluster is called <name>-msk."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the cluster."
  type        = string
}

variable "subnet_ids" {
  description = "Client subnets, one per broker AZ. The broker count must be a multiple of this list's length."
  type        = list(string)
}

variable "allowed_security_groups" {
  description = "Map of label => security group ID allowed to reach the brokers on 9092/9094. Keys must be static strings."
  type        = map(string)
}

variable "kafka_version" {
  description = "Apache Kafka version."
  type        = string
  default     = "3.8.x" # MSK names 3.7+ as "3.N.x" (aws kafka list-kafka-versions)
}

variable "broker_instance_type" {
  description = "Broker instance type."
  type        = string
  default     = "kafka.t3.small"
}

variable "broker_count" {
  description = "Number of broker nodes."
  type        = number
  default     = 2
}

variable "broker_volume_size" {
  description = "EBS volume per broker, in GiB."
  type        = number
  default     = 10
}

variable "log_retention_days" {
  description = "CloudWatch retention of the broker logs."
  type        = number
  default     = 7
}
