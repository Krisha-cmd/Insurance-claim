locals {
  buckets = {
    documents = var.documents_bucket_name
    emr_logs  = var.emr_logs_bucket_name
    artifacts = var.artifacts_bucket_name
  }
}

resource "aws_s3_bucket" "this" {
  for_each = local.buckets

  bucket        = each.value
  force_destroy = true # demo: `terraform destroy` also empties the bucket
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = aws_s3_bucket.this

  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# ---- documents: versioning, CORS for browser uploads, lifecycle, event notification
resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.this["documents"].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_cors_configuration" "documents" {
  bucket = aws_s3_bucket.this["documents"].id

  cors_rule {
    allowed_methods = ["PUT", "GET"]
    allowed_origins = var.frontend_origins
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "documents" {
  bucket = aws_s3_bucket.this["documents"].id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.documents]
}

resource "aws_s3_bucket_notification" "documents" {
  bucket = aws_s3_bucket.this["documents"].id

  queue {
    id            = "doc-upload-events"
    queue_arn     = var.upload_events_queue_arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = var.notification_prefix
  }
}

# ---- emr-logs: expire old logs
resource "aws_s3_bucket_lifecycle_configuration" "emr_logs" {
  bucket = aws_s3_bucket.this["emr_logs"].id

  rule {
    id     = "expire-logs"
    status = "Enabled"
    filter {}

    expiration {
      days = var.emr_log_expiration_days
    }
  }
}
