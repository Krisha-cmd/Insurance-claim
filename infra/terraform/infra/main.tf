data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  prefix       = "${var.project}-${var.env}"
  cluster_name = local.prefix
  account_id   = data.aws_caller_identity.current.account_id

  # Bucket names are global, so the account ID keeps them unique.
  documents_bucket = "${local.prefix}-documents-${local.account_id}"
  emr_logs_bucket  = "${local.prefix}-emr-logs-${local.account_id}"
  artifacts_bucket = "${local.prefix}-artifacts-${local.account_id}"

  lambda_function_name = "${local.prefix}-document-verification"
  msk_cluster_name     = "${local.prefix}-msk"
}

# 1. Network --------------------------------------------------------------
module "network" {
  source = "../modules/network"

  name               = local.prefix
  cluster_name       = local.cluster_name
  vpc_cidr           = var.vpc_cidr
  az_count           = var.az_count
  single_nat_gateway = var.single_nat_gateway
}

# 2. Kubernetes -----------------------------------------------------------
module "eks" {
  source = "../modules/eks"

  cluster_name                 = local.cluster_name
  cluster_version              = var.cluster_version
  vpc_id                       = module.network.vpc_id
  private_subnet_ids           = module.network.private_subnet_ids
  node_instance_types          = var.node_instance_types
  node_min_size                = var.node_min_size
  node_desired_size            = var.node_desired_size
  node_max_size                = var.node_max_size
  endpoint_public_access_cidrs = var.eks_public_access_cidrs
}

# 3. Container registry ---------------------------------------------------
module "ecr" {
  source = "../modules/ecr"

  name         = local.prefix
  repositories = var.ecr_repositories
}

# 4. Queues and buckets (the bucket ARN is computed up front to avoid an S3 <-> SQS cycle)
module "sqs" {
  source = "../modules/sqs"

  name                   = local.prefix
  documents_bucket_arn   = "arn:aws:s3:::${local.documents_bucket}"
  lambda_timeout_seconds = var.lambda_timeout_seconds
}

module "s3" {
  source = "../modules/s3"

  documents_bucket_name   = local.documents_bucket
  emr_logs_bucket_name    = local.emr_logs_bucket
  artifacts_bucket_name   = local.artifacts_bucket
  frontend_origins        = var.frontend_origins
  upload_events_queue_arn = module.sqs.upload_events_queue_arn
}

# 5. Analytics table ------------------------------------------------------
module "dynamodb" {
  source = "../modules/dynamodb"
  count  = var.enable_dynamodb ? 1 : 0

  name = local.prefix
}

# 6. Flink on EMR ---------------------------------------------------------
module "emr" {
  source = "../modules/emr"
  count  = var.enable_emr ? 1 : 0

  name                  = local.prefix
  vpc_id                = module.network.vpc_id
  subnet_id             = module.network.private_subnet_ids[0]
  release_label         = var.emr_release_label
  primary_instance_type = var.emr_primary_instance_type
  core_instance_type    = var.emr_core_instance_type
  core_instance_count   = var.emr_core_instance_count
  idle_timeout_seconds  = var.emr_idle_timeout_seconds
  logs_bucket_name      = module.s3.emr_logs_bucket_name
  logs_bucket_arn       = module.s3.emr_logs_bucket_arn
  artifacts_bucket_name = module.s3.artifacts_bucket_name
  artifacts_bucket_arn  = module.s3.artifacts_bucket_arn
  enable_dynamodb       = var.enable_dynamodb
  analytics_table_arn   = try(module.dynamodb[0].table_arn, null)
  msk_cluster_name      = local.msk_cluster_name
}

# 7. Kafka ----------------------------------------------------------------
module "msk" {
  source = "../modules/msk"
  count  = var.enable_msk ? 1 : 0

  name                 = local.prefix
  vpc_id               = module.network.vpc_id
  subnet_ids           = module.network.private_subnet_ids
  broker_instance_type = var.msk_broker_instance_type
  broker_count         = var.msk_broker_count
  broker_volume_size   = var.msk_broker_volume_size

  # Static keys (known at plan time) even though the IDs are not.
  allowed_security_groups = merge(
    { eks_nodes = module.eks.node_security_group_id },
    var.enable_emr ? {
      emr_primary = module.emr[0].master_security_group_id
      emr_core    = module.emr[0].slave_security_group_id
    } : {},
  )
}

# 8. PostgreSQL -----------------------------------------------------------
module "rds" {
  source = "../modules/rds"
  count  = var.enable_rds ? 1 : 0

  name              = local.prefix
  vpc_id            = module.network.vpc_id
  subnet_ids        = module.network.private_subnet_ids
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage

  allowed_security_groups = merge(
    { eks_nodes = module.eks.node_security_group_id },
    var.rds_extra_allowed_security_groups,
  )
}

# 9. IAM (IRSA roles, Lambda role, optional CI role) ---------------------
module "iam" {
  source = "../modules/iam"

  name                    = local.prefix
  oidc_provider_arn       = module.eks.oidc_provider_arn
  oidc_provider_url       = module.eks.oidc_provider_url
  documents_bucket_arn    = module.s3.documents_bucket_arn
  upload_events_queue_arn = module.sqs.upload_events_queue_arn
  results_queue_arn       = module.sqs.results_queue_arn
  enable_dynamodb         = var.enable_dynamodb
  analytics_table_arn     = try(module.dynamodb[0].table_arn, null)
  enable_lambda           = var.enable_lambda
  lambda_function_name    = local.lambda_function_name
  enable_github_oidc      = var.enable_github_oidc
  github_repository       = var.github_repository
  ecr_repository_arns     = values(module.ecr.repository_arns)
}

# 10. Serverless document verification -----------------------------------
module "lambda" {
  source = "../modules/lambda"
  count  = var.enable_lambda ? 1 : 0

  function_name           = local.lambda_function_name
  role_arn                = module.iam.lambda_role_arn
  jar_path                = var.lambda_jar_path
  handler                 = var.lambda_handler
  timeout_seconds         = var.lambda_timeout_seconds
  upload_events_queue_arn = module.sqs.upload_events_queue_arn
  results_queue_url       = module.sqs.results_queue_url
  documents_bucket_name   = module.s3.documents_bucket_name
}
