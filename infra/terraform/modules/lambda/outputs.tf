output "function_name" {
  description = "Name of the function."
  value       = aws_lambda_function.this.function_name
}

output "function_arn" {
  description = "Unqualified ARN of the function."
  value       = aws_lambda_function.this.arn
}

output "alias_arn" {
  description = "ARN of the `live` alias (the event source mapping target)."
  value       = aws_lambda_alias.live.arn
}

output "log_group_name" {
  description = "CloudWatch log group of the function."
  value       = aws_cloudwatch_log_group.this.name
}
