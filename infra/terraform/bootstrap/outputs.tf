output "state_bucket" {
  description = "S3 bucket for remote state. Pass it as -backend-config=\"bucket=...\"."
  value       = aws_s3_bucket.tfstate.bucket
}

output "lock_table" {
  description = "DynamoDB lock table. Pass it as -backend-config=\"dynamodb_table=...\"."
  value       = aws_dynamodb_table.locks.name
}

output "region" {
  description = "Region of the state bucket. Pass it as -backend-config=\"region=...\"."
  value       = var.region
}

output "backend_config_args" {
  description = "Ready-to-paste -backend-config arguments for terraform init in infra/ and platform/."
  value       = "-backend-config=\"bucket=${aws_s3_bucket.tfstate.bucket}\" -backend-config=\"region=${var.region}\" -backend-config=\"dynamodb_table=${aws_dynamodb_table.locks.name}\""
}
