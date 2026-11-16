output "role_arns" {
  description = "Map of role name => ARN. Disabled roles are null."
  value = {
    claim_intake   = aws_iam_role.irsa["claim-intake"].arn
    ops_dashboard  = try(aws_iam_role.irsa["ops-dashboard"].arn, null)
    alb_controller = aws_iam_role.irsa["aws-load-balancer-controller"].arn
    grafana        = aws_iam_role.irsa["grafana"].arn
    lambda         = try(aws_iam_role.lambda[0].arn, null)
    github_actions = try(aws_iam_role.github_actions[0].arn, null)
  }
}

output "lambda_role_arn" {
  description = "Lambda execution role ARN. Depends on its policy so the event source mapping is created only once SQS permissions exist."
  value       = try(aws_iam_role.lambda[0].arn, null)
  depends_on  = [aws_iam_role_policy.lambda]
}
