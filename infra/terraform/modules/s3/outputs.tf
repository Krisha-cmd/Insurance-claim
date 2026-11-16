output "documents_bucket_name" {
  description = "Name of the claim documents bucket."
  value       = aws_s3_bucket.this["documents"].bucket
}

output "documents_bucket_arn" {
  description = "ARN of the claim documents bucket."
  value       = aws_s3_bucket.this["documents"].arn
}

output "emr_logs_bucket_name" {
  description = "Name of the EMR logs bucket."
  value       = aws_s3_bucket.this["emr_logs"].bucket
}

output "emr_logs_bucket_arn" {
  description = "ARN of the EMR logs bucket."
  value       = aws_s3_bucket.this["emr_logs"].arn
}

output "artifacts_bucket_name" {
  description = "Name of the artifacts bucket."
  value       = aws_s3_bucket.this["artifacts"].bucket
}

output "artifacts_bucket_arn" {
  description = "ARN of the artifacts bucket."
  value       = aws_s3_bucket.this["artifacts"].arn
}
