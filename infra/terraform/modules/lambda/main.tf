locals {
  jar_exists = fileexists(var.jar_path)
}

# Created explicitly (instead of letting Lambda create it) so retention is set and destroy removes it.
resource "aws_cloudwatch_log_group" "this" {
  name              = "/aws/lambda/${var.function_name}"
  retention_in_days = var.log_retention_days
}

# Deliberately NOT in the VPC: it only talks to S3 and SQS public endpoints,
# which avoids ENI cold starts and NAT gateway data charges.
resource "aws_lambda_function" "this" {
  function_name = var.function_name
  role          = var.role_arn
  runtime       = var.runtime
  handler       = var.handler
  memory_size   = var.memory_mb
  timeout       = var.timeout_seconds
  architectures = ["x86_64"]

  filename         = var.jar_path
  source_code_hash = local.jar_exists ? filebase64sha256(var.jar_path) : null
  publish          = true # SnapStart works on published versions only

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      RESULTS_QUEUE_URL = var.results_queue_url
      DOCUMENTS_BUCKET  = var.documents_bucket_name
      ALLOWED_TYPES     = var.allowed_types
      MAX_SIZE_MB       = tostring(var.max_size_mb)
    }
  }

  lifecycle {
    precondition {
      condition     = local.jar_exists
      error_message = <<-EOT
        Lambda jar not found at "${var.jar_path}" (relative to the directory you run terraform in).
        Either build it first:
            cd backend && mvn -pl functions/document-verification -am package
        or skip the Lambda for now by setting enable_lambda = false in dev.tfvars.
      EOT
    }
  }

  depends_on = [aws_cloudwatch_log_group.this]
}

# The event source mapping points at this alias, so a new published version is
# picked up atomically when the alias moves.
resource "aws_lambda_alias" "live" {
  name             = "live"
  function_name    = aws_lambda_function.this.function_name
  function_version = aws_lambda_function.this.version
}

resource "aws_lambda_event_source_mapping" "uploads" {
  event_source_arn                   = var.upload_events_queue_arn
  function_name                      = aws_lambda_alias.live.arn
  batch_size                         = var.batch_size
  maximum_batching_window_in_seconds = 1
  function_response_types            = ["ReportBatchItemFailures"] # retry only the failed messages of a batch
}
