variable "region" {
  description = "AWS region for the state bucket and lock table. Use the same region as the other stacks."
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  description = "Project name, used as the first part of every resource name."
  type        = string
  default     = "claims"
}

variable "env" {
  description = "Environment name, used as the second part of every resource name."
  type        = string
  default     = "dev"
}
