data "aws_caller_identity" "current" {}

locals {
  state_bucket = var.state_bucket != "" ? var.state_bucket : "${var.project}-${var.env}-tfstate-${data.aws_caller_identity.current.account_id}"
}

# Every AWS endpoint/ARN this stack needs comes from the infra stack's outputs.
data "terraform_remote_state" "infra" {
  backend = "s3"

  config = {
    bucket = local.state_bucket
    key    = "infra/terraform.tfstate"
    region = var.region
  }
}

locals {
  infra = data.terraform_remote_state.infra.outputs

  # Feature flags follow what the infra stack actually created. They are derived
  # from non-sensitive outputs so they can drive `count`.
  rds_enabled      = local.infra.rds_endpoint != null
  msk_enabled      = local.infra.msk_bootstrap_brokers != null
  dynamodb_enabled = local.infra.analytics_table_name != null

  roles = local.infra.irsa_role_arns
}

# Note: the kubernetes/helm providers authenticate with `exec` (`aws eks get-token`,
# see providers.tf) instead of an `aws_eks_cluster_auth` data source. That token is
# read once at plan time and expires after 15 minutes, which a slow Helm install
# can outlast; `exec` fetches a fresh token whenever one is needed.

# RDS-managed master credentials (JSON with username/password).
data "aws_secretsmanager_secret_version" "rds_master" {
  count = local.rds_enabled ? 1 : 0

  secret_id = local.infra.rds_master_secret_arn
}
