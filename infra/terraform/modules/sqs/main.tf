locals {
  visibility_timeout = 6 * var.lambda_timeout_seconds
}

# ---- doc-upload-events: S3 ObjectCreated notifications -> Document Verification Lambda
resource "aws_sqs_queue" "upload_dlq" {
  name                      = "${var.name}-doc-upload-events-dlq"
  message_retention_seconds = 1209600 # 14 days, the maximum, to give time to inspect failures
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "upload" {
  name                       = "${var.name}-doc-upload-events"
  visibility_timeout_seconds = local.visibility_timeout
  message_retention_seconds  = var.message_retention_seconds
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.upload_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}

data "aws_caller_identity" "current" {}

# Lets the documents bucket (and only that bucket, in this account) publish events.
resource "aws_sqs_queue_policy" "upload" {
  queue_url = aws_sqs_queue.upload.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowDocumentsBucket"
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.upload.arn
      Condition = {
        ArnEquals    = { "aws:SourceArn" = var.documents_bucket_arn }
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })
}

# ---- doc-verification-results: Lambda -> Claim Intake
resource "aws_sqs_queue" "results_dlq" {
  name                      = "${var.name}-doc-verification-results-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "results" {
  name                       = "${var.name}-doc-verification-results"
  visibility_timeout_seconds = local.visibility_timeout
  message_retention_seconds  = var.message_retention_seconds
  receive_wait_time_seconds  = 20 # long polling: fewer empty receives from Claim Intake
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.results_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}
