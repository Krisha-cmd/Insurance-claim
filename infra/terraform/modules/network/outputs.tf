output "vpc_id" {
  description = "ID of the VPC."
  value       = module.vpc.vpc_id
}

output "vpc_cidr" {
  description = "CIDR block of the VPC."
  value       = module.vpc.vpc_cidr_block
}

output "private_subnet_ids" {
  description = "Private subnet IDs (one per AZ). EKS nodes, RDS, MSK and EMR live here."
  value       = module.vpc.private_subnets
}

output "public_subnet_ids" {
  description = "Public subnet IDs (one per AZ). Internet-facing ALBs and the NAT gateway live here."
  value       = module.vpc.public_subnets
}

output "azs" {
  description = "Availability zones in use."
  value       = local.azs
}
