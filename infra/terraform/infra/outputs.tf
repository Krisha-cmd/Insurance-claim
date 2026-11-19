# Everything the platform stack (via terraform_remote_state) and the README need.
# Outputs of disabled components are null.

output "region" {
  description = "AWS region."
  value       = var.region
}

output "project" {
  description = "Project name."
  value       = var.project
}

output "env" {
  description = "Environment name."
  value       = var.env
}

output "account_id" {
  description = "AWS account ID."
  value       = local.account_id
}

# ---- network
output "vpc_id" {
  description = "VPC ID (the ALB controller needs it)."
  value       = module.network.vpc_id
}

output "private_subnet_ids" {
  description = "Private subnet IDs."
  value       = module.network.private_subnet_ids
}

output "public_subnet_ids" {
  description = "Public subnet IDs."
  value       = module.network.public_subnet_ids
}

# ---- EKS
output "cluster_name" {
  description = "EKS cluster name."
  value       = module.eks.cluster_name
}

output "cluster_endpoint" {
  description = "Kubernetes API endpoint."
  value       = module.eks.cluster_endpoint
}

output "cluster_ca" {
  description = "Base64 cluster CA certificate."
  value       = module.eks.cluster_ca
}

output "oidc_provider_arn" {
  description = "IAM OIDC provider ARN of the cluster."
  value       = module.eks.oidc_provider_arn
}

output "node_security_group_id" {
  description = "Security group of EKS nodes and pods."
  value       = module.eks.node_security_group_id
}

output "kubeconfig_command" {
  description = "Command that adds this cluster to ~/.kube/config."
  value       = "aws eks update-kubeconfig --name ${module.eks.cluster_name} --region ${var.region}"
}

# ---- ECR
output "ecr_repository_urls" {
  description = "Map of image name => ECR repository URL."
  value       = module.ecr.repository_urls
}

output "ecr_registry" {
  description = "ECR registry host, for `docker login`."
  value       = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

# ---- RDS
output "rds_endpoint" {
  description = "PostgreSQL hostname."
  value       = try(module.rds[0].endpoint, null)
}

output "rds_port" {
  description = "PostgreSQL port."
  value       = try(module.rds[0].port, null)
}

output "rds_master_username" {
  description = "PostgreSQL master user name."
  value       = try(module.rds[0].master_username, null)
}

output "rds_master_secret_arn" {
  description = "Secrets Manager ARN of the master credentials."
  value       = try(module.rds[0].master_secret_arn, null)
  sensitive   = true
}

output "rds_instance_id" {
  description = "PostgreSQL instance identifier (CloudWatch dimension; used by the Grafana dashboard)."
  value       = try(module.rds[0].identifier, null)
}

output "rds_security_group_id" {
  description = "Security group of the PostgreSQL instance."
  value       = try(module.rds[0].security_group_id, null)
}

# ---- MSK
output "msk_bootstrap_brokers" {
  description = "Kafka plaintext bootstrap brokers (port 9092)."
  value       = try(module.msk[0].bootstrap_brokers, null)
}

output "msk_bootstrap_brokers_tls" {
  description = "Kafka TLS bootstrap brokers (port 9094)."
  value       = try(module.msk[0].bootstrap_brokers_tls, null)
}

output "msk_cluster_name" {
  description = "MSK cluster name (CloudWatch metrics dimension)."
  value       = try(module.msk[0].cluster_name, null)
}

# ---- S3
output "documents_bucket" {
  description = "Claim documents bucket."
  value       = module.s3.documents_bucket_name
}

output "artifacts_bucket" {
  description = "Artifacts bucket (Flink jar)."
  value       = module.s3.artifacts_bucket_name
}

output "emr_logs_bucket" {
  description = "EMR logs bucket."
  value       = module.s3.emr_logs_bucket_name
}

# ---- SQS
output "upload_events_queue_url" {
  description = "doc-upload-events queue URL."
  value       = module.sqs.upload_events_queue_url
}

output "results_queue_url" {
  description = "doc-verification-results queue URL (Claim Intake consumes it)."
  value       = module.sqs.results_queue_url
}

output "upload_events_dlq_url" {
  description = "Dead-letter queue of doc-upload-events."
  value       = module.sqs.upload_events_dlq_url
}

output "results_dlq_url" {
  description = "Dead-letter queue of doc-verification-results."
  value       = module.sqs.results_dlq_url
}

# ---- DynamoDB
output "analytics_table_name" {
  description = "DynamoDB analytics/audit table."
  value       = try(module.dynamodb[0].table_name, null)
}

# ---- EMR
output "emr_cluster_id" {
  description = "EMR cluster ID for `aws emr add-steps`."
  value       = try(module.emr[0].cluster_id, null)
}

output "emr_flink_log_group" {
  description = "CloudWatch log group with Flink logs."
  value       = try(module.emr[0].flink_log_group, null)
}

# ---- Lambda
output "lambda_function_name" {
  description = "Document Verification function name."
  value       = try(module.lambda[0].function_name, null)
}

output "lambda_log_group" {
  description = "Document Verification log group."
  value       = try(module.lambda[0].log_group_name, null)
}

# ---- IAM
output "irsa_role_arns" {
  description = "IRSA and other role ARNs (claim_intake, ops_dashboard, alb_controller, grafana, lambda, github_actions)."
  value       = module.iam.role_arns
}
