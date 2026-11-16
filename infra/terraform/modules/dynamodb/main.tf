# Single-table design for the audit trail and the Flink analytics windows.
#   PK / SK          e.g. CLAIM#CLM-1 / EVT#2026-11-16T10:00:00Z
#   GSI1PK / GSI1SK  e.g. WINDOW#1m   / 2026-11-16T10:00
#   ttl              epoch seconds; DynamoDB deletes expired analytics rows for free
resource "aws_dynamodb_table" "analytics" {
  name                        = "${var.name}-analytics"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "PK"
  range_key                   = "SK"
  deletion_protection_enabled = false

  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }

  attribute {
    name = "GSI1PK"
    type = "S"
  }

  attribute {
    name = "GSI1SK"
    type = "S"
  }

  global_secondary_index {
    name            = "GSI1"
    hash_key        = "GSI1PK"
    range_key       = "GSI1SK"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }
}
