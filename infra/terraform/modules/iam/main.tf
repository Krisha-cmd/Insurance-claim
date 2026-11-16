data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
data "aws_partition" "current" {}

locals {
  partition = data.aws_partition.current.partition

  # IRSA roles: each is trusted only by one namespace/service account pair.
  irsa = merge(
    {
      claim-intake                 = { namespace = var.app_namespace, service_account = "claim-intake" }
      aws-load-balancer-controller = { namespace = "kube-system", service_account = "aws-load-balancer-controller" }
      grafana                      = { namespace = var.observability_namespace, service_account = "grafana" }
    },
    var.enable_dynamodb ? {
      ops-dashboard = { namespace = var.app_namespace, service_account = "ops-dashboard" }
    } : {},
  )
}

data "aws_iam_policy_document" "irsa_trust" {
  for_each = local.irsa

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${each.value.namespace}:${each.value.service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "irsa" {
  for_each = local.irsa

  name               = "${var.name}-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust[each.key].json
}

# ---- claim-intake: pre-signed document URLs + verification results
resource "aws_iam_role_policy" "claim_intake" {
  name = "claim-intake"
  role = aws_iam_role.irsa["claim-intake"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "PresignDocuments"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "${var.documents_bucket_arn}/${var.documents_prefix}*"
      },
      {
        Sid      = "ConsumeVerificationResults"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"]
        Resource = var.results_queue_arn
      },
    ]
  })
}

# ---- ops-dashboard: read analytics
resource "aws_iam_role_policy" "ops_dashboard" {
  count = var.enable_dynamodb ? 1 : 0

  name = "ops-dashboard"
  role = aws_iam_role.irsa["ops-dashboard"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "ReadAnalytics"
      Effect   = "Allow"
      Action   = ["dynamodb:Query", "dynamodb:GetItem", "dynamodb:DescribeTable"]
      Resource = [var.analytics_table_arn, "${var.analytics_table_arn}/index/*"]
    }]
  })
}

# ---- AWS Load Balancer Controller: official policy (v2.8.3)
resource "aws_iam_policy" "alb_controller" {
  name        = "${var.name}-aws-load-balancer-controller"
  description = "Official AWS Load Balancer Controller policy (v2.8.3)"
  policy      = file("${path.module}/policies/alb-controller.json")
}

resource "aws_iam_role_policy_attachment" "alb_controller" {
  role       = aws_iam_role.irsa["aws-load-balancer-controller"].name
  policy_arn = aws_iam_policy.alb_controller.arn
}

# ---- grafana: read CloudWatch Logs (EMR/Flink, Lambda) and metrics (MSK, SQS, RDS)
resource "aws_iam_role_policy" "grafana" {
  name = "grafana-cloudwatch-read"
  role = aws_iam_role.irsa["grafana"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadLogs"
        Effect = "Allow"
        Action = [
          "logs:DescribeLogGroups",
          "logs:GetLogGroupFields",
          "logs:StartQuery",
          "logs:StopQuery",
          "logs:GetQueryResults",
          "logs:GetLogEvents",
          "logs:FilterLogEvents",
        ]
        Resource = "*"
      },
      {
        Sid    = "ReadMetrics"
        Effect = "Allow"
        Action = [
          "cloudwatch:DescribeAlarmsForMetric",
          "cloudwatch:DescribeAlarmHistory",
          "cloudwatch:DescribeAlarms",
          "cloudwatch:ListMetrics",
          "cloudwatch:GetMetricData",
          "cloudwatch:GetInsightRuleReport",
        ]
        Resource = "*"
      },
      {
        Sid      = "ResourceDiscovery"
        Effect   = "Allow"
        Action   = ["tag:GetResources", "ec2:DescribeTags", "ec2:DescribeInstances", "ec2:DescribeRegions"]
        Resource = "*"
      },
    ]
  })
}

# ---------------------------------------------------------------------------
# Lambda execution role (Document Verification)
# ---------------------------------------------------------------------------
resource "aws_iam_role" "lambda" {
  count = var.enable_lambda ? 1 : 0

  name = "${var.name}-document-verification"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "lambda" {
  count = var.enable_lambda ? 1 : 0

  name = "document-verification"
  role = aws_iam_role.lambda[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadDocuments"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion", "s3:GetObjectTagging"]
        Resource = "${var.documents_bucket_arn}/*"
      },
      {
        Sid      = "ConsumeUploadEvents"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"]
        Resource = var.upload_events_queue_arn
      },
      {
        Sid      = "PublishResults"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage", "sqs:GetQueueUrl"]
        Resource = var.results_queue_arn
      },
      {
        Sid      = "WriteLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "arn:${local.partition}:logs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:log-group:/aws/lambda/${var.lambda_function_name}:*"
      },
    ]
  })
}

# ---------------------------------------------------------------------------
# Optional: GitHub Actions OIDC -> push images to ECR, no long-lived keys
# ---------------------------------------------------------------------------
resource "aws_iam_openid_connect_provider" "github" {
  count = var.enable_github_oidc ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1", "1c58a3a8518e8759bf075b76b750d4f2df264fcd"]
}

resource "aws_iam_role" "github_actions" {
  count = var.enable_github_oidc ? 1 : 0

  name = "${var.name}-github-actions-ecr"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github[0].arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike   = { "token.actions.githubusercontent.com:sub" = "repo:${var.github_repository}:*" }
      }
    }]
  })

  lifecycle {
    precondition {
      condition     = can(regex("^[^/]+/[^/]+$", var.github_repository))
      error_message = "Set github_repository to \"owner/name\" when enable_github_oidc = true."
    }
  }
}

resource "aws_iam_role_policy" "github_actions" {
  count = var.enable_github_oidc ? 1 : 0

  name = "ecr-push"
  role = aws_iam_role.github_actions[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "Login"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "Push"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeImages",
          "ecr:GetDownloadUrlForLayer",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
        ]
        Resource = var.ecr_repository_arns
      },
    ]
  })
}
