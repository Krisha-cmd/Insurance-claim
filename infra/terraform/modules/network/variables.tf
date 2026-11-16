variable "name" {
  description = "Name prefix for the VPC and its resources (e.g. claims-dev)."
  type        = string
}

variable "cluster_name" {
  description = "EKS cluster name, used for the kubernetes.io/cluster/<name> subnet tag."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones to spread subnets across."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2
    error_message = "EKS, RDS subnet groups and MSK need at least 2 availability zones."
  }
}

variable "single_nat_gateway" {
  description = "Use one shared NAT gateway (cheap) instead of one per AZ (highly available)."
  type        = bool
  default     = true
}
