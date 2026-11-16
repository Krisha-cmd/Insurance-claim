output "cluster_id" {
  description = "ID of the EMR cluster (j-XXXXXXXX), used with `aws emr add-steps`."
  value       = aws_emr_cluster.this.id
}

output "master_public_dns" {
  description = "Private DNS name of the primary node (reachable from inside the VPC)."
  value       = aws_emr_cluster.this.master_public_dns
}

output "master_security_group_id" {
  description = "EMR managed security group of the primary node."
  value       = aws_security_group.primary.id
}

output "slave_security_group_id" {
  description = "EMR managed security group of the core nodes."
  value       = aws_security_group.core.id
}

output "flink_log_group" {
  description = "CloudWatch log group receiving Flink and YARN container logs."
  value       = aws_cloudwatch_log_group.flink.name
}

output "instance_role_arn" {
  description = "IAM role of the EMR EC2 instances."
  value       = aws_iam_role.instance.arn
}
