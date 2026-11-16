output "endpoint" {
  description = "Hostname of the instance (without port)."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Port of the instance."
  value       = aws_db_instance.this.port
}

output "master_username" {
  description = "Master user name."
  value       = aws_db_instance.this.username
}

output "master_secret_arn" {
  description = "ARN of the Secrets Manager secret holding the master credentials (JSON: username, password)."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "security_group_id" {
  description = "Security group of the instance."
  value       = aws_security_group.this.id
}
