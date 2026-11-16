output "bootstrap_brokers" {
  description = "Plaintext bootstrap brokers (host:9092,...)."
  value       = aws_msk_cluster.this.bootstrap_brokers
}

output "bootstrap_brokers_tls" {
  description = "TLS bootstrap brokers (host:9094,...)."
  value       = aws_msk_cluster.this.bootstrap_brokers_tls
}

output "security_group_id" {
  description = "Security group of the brokers."
  value       = aws_security_group.this.id
}

output "cluster_arn" {
  description = "ARN of the MSK cluster."
  value       = aws_msk_cluster.this.arn
}

output "cluster_name" {
  description = "Name of the MSK cluster (used as the CloudWatch AWS/Kafka metrics dimension)."
  value       = aws_msk_cluster.this.cluster_name
}
