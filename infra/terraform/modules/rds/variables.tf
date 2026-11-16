variable "name" {
  description = "Name prefix (e.g. claims-dev); the instance is called <name>-postgres."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the database."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets for the DB subnet group (at least 2 AZs)."
  type        = list(string)
}

variable "allowed_security_groups" {
  description = "Map of label => security group ID allowed to connect on 5432 (EKS nodes, plus any extras). Keys must be static strings."
  type        = map(string)
}

variable "engine_version" {
  description = "PostgreSQL major (or major.minor) version."
  type        = string
  default     = "16"
}

variable "instance_class" {
  description = "Instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "Storage size in GiB (gp3)."
  type        = number
  default     = 20
}

variable "master_username" {
  description = "Master user name. Its password is generated and stored in Secrets Manager by RDS."
  type        = string
  default     = "claims_admin"
}

variable "backup_retention_days" {
  description = "Days of automated backups to keep."
  type        = number
  default     = 1
}
