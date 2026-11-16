data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
data "aws_partition" "current" {}

locals {
  account   = data.aws_caller_identity.current.account_id
  region    = data.aws_region.current.name
  partition = data.aws_partition.current.partition

  log_group_name   = "/aws/emr/${var.name}/flink"
  bootstrap_key    = "emr/bootstrap/cloudwatch-agent.sh"
  msk_resource_arn = "arn:${local.partition}:kafka:${local.region}:${local.account}"
}

# ---------------------------------------------------------------------------
# Security groups. EMR adds its own rules to "managed" groups, so Terraform
# ignores rule drift and revokes everything on delete (otherwise destroy
# hangs on DependencyViolation between the cross-referencing groups).
# ---------------------------------------------------------------------------
resource "aws_security_group" "primary" {
  name                   = "${var.name}-emr-primary"
  description            = "EMR managed: primary node"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    ignore_changes = [ingress, egress]
  }

  tags = { Name = "${var.name}-emr-primary" }
}

resource "aws_security_group" "core" {
  name                   = "${var.name}-emr-core"
  description            = "EMR managed: core and task nodes"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    ignore_changes = [ingress, egress]
  }

  tags = { Name = "${var.name}-emr-core" }
}

# Required for clusters in a private subnet: the EMR service talks to the primary node on 9443.
resource "aws_security_group" "service_access" {
  name                   = "${var.name}-emr-service-access"
  description            = "EMR managed: service access for private subnet clusters"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true

  ingress {
    description     = "EMR service from primary node"
    from_port       = 9443
    to_port         = 9443
    protocol        = "tcp"
    security_groups = [aws_security_group.primary.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    ignore_changes = [ingress, egress]
  }

  tags = { Name = "${var.name}-emr-service-access" }
}

# ---------------------------------------------------------------------------
# IAM: service role + EC2 instance profile
# ---------------------------------------------------------------------------
resource "aws_iam_role" "service" {
  name = "${var.name}-emr-service"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "elasticmapreduce.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "service" {
  role       = aws_iam_role.service.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonElasticMapReduceRole"
}

resource "aws_iam_role" "instance" {
  name = "${var.name}-emr-instance"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

locals {
  instance_statements = concat(
    [
      {
        Sid      = "ReadArtifacts"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:ListBucket"]
        Resource = [var.artifacts_bucket_arn, "${var.artifacts_bucket_arn}/*"]
      },
      {
        Sid      = "WriteLogs"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:ListBucket", "s3:AbortMultipartUpload"]
        Resource = [var.logs_bucket_arn, "${var.logs_bucket_arn}/*"]
      },
      {
        Sid    = "ReadEmrOwnedBuckets"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:ListBucket"]
        Resource = [
          "arn:${local.partition}:s3:::${local.region}.elasticmapreduce",
          "arn:${local.partition}:s3:::${local.region}.elasticmapreduce/*",
          "arn:${local.partition}:s3:::repo.${local.region}.emr.amazonaws.com",
          "arn:${local.partition}:s3:::repo.${local.region}.emr.amazonaws.com/*",
        ]
      },
      {
        Sid    = "EmrNodeDiscovery"
        Effect = "Allow"
        Action = [
          "elasticmapreduce:Describe*",
          "elasticmapreduce:ListBootstrapActions",
          "elasticmapreduce:ListClusters",
          "elasticmapreduce:ListInstanceGroups",
          "elasticmapreduce:ListInstances",
          "elasticmapreduce:ListSteps",
          "ec2:Describe*",
        ]
        Resource = "*"
      },
      {
        Sid    = "ConnectToMsk"
        Effect = "Allow"
        Action = [
          "kafka-cluster:Connect",
          "kafka-cluster:DescribeCluster",
          "kafka-cluster:DescribeTopic",
          "kafka-cluster:CreateTopic",
          "kafka-cluster:ReadData",
          "kafka-cluster:WriteData",
          "kafka-cluster:DescribeGroup",
          "kafka-cluster:AlterGroup",
        ]
        Resource = [
          "${local.msk_resource_arn}:cluster/${var.msk_cluster_name}/*",
          "${local.msk_resource_arn}:topic/${var.msk_cluster_name}/*",
          "${local.msk_resource_arn}:group/${var.msk_cluster_name}/*",
        ]
      },
      {
        Sid      = "DiscoverMskBrokers"
        Effect   = "Allow"
        Action   = ["kafka:GetBootstrapBrokers", "kafka:DescribeCluster", "kafka:DescribeClusterV2", "kafka:ListClusters"]
        Resource = "*"
      },
    ],
    var.enable_dynamodb ? [{
      Sid    = "WriteAnalyticsTable"
      Effect = "Allow"
      Action = [
        "dynamodb:PutItem",
        "dynamodb:UpdateItem",
        "dynamodb:BatchWriteItem",
        "dynamodb:GetItem",
        "dynamodb:Query",
        "dynamodb:DescribeTable",
      ]
      Resource = [var.analytics_table_arn, "${var.analytics_table_arn}/index/*"]
    }] : [],
  )
}

resource "aws_iam_role_policy" "instance" {
  name   = "flink-job"
  role   = aws_iam_role.instance.id
  policy = jsonencode({ Version = "2012-10-17", Statement = local.instance_statements })
}

# CloudWatch agent (ships Flink logs) and SSM Session Manager (shell access without SSH keys).
resource "aws_iam_role_policy_attachment" "instance_managed" {
  for_each = toset([
    "arn:${local.partition}:iam::aws:policy/CloudWatchAgentServerPolicy",
    "arn:${local.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore",
  ])

  role       = aws_iam_role.instance.name
  policy_arn = each.value
}

resource "aws_iam_instance_profile" "this" {
  name = "${var.name}-emr-instance"
  role = aws_iam_role.instance.name
}

# ---------------------------------------------------------------------------
# Centralised logging: Flink JobManager/TaskManager logs -> CloudWatch Logs
# (Grafana reads them through its CloudWatch datasource). EMR also archives
# all logs to s3://<logs bucket>/ via log_uri.
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "flink" {
  name              = local.log_group_name
  retention_in_days = var.log_retention_days
}

resource "aws_s3_object" "cloudwatch_bootstrap" {
  bucket       = var.artifacts_bucket_name
  key          = local.bootstrap_key
  content_type = "text/x-shellscript"
  content      = <<-SCRIPT
    #!/bin/bash
    # Installs the CloudWatch agent and ships Flink/YARN container logs to ${local.log_group_name}.
    # Never fails the bootstrap: a logging problem must not stop the cluster from starting.
    set -u
    log() { echo "[cloudwatch-bootstrap] $*"; }

    if ! sudo dnf install -y amazon-cloudwatch-agent >/dev/null 2>&1; then
      sudo yum install -y amazon-cloudwatch-agent >/dev/null 2>&1 || { log "agent install failed, skipping"; exit 0; }
    fi

    sudo mkdir -p /opt/aws/amazon-cloudwatch-agent/etc
    sudo tee /opt/aws/amazon-cloudwatch-agent/etc/claims-flink.json >/dev/null <<'JSON'
    {
      "logs": {
        "logs_collected": {
          "files": {
            "collect_list": [
              {
                "file_path": "/var/log/hadoop-yarn/containers/application_*/container_*/*.log",
                "log_group_name": "${local.log_group_name}",
                "log_stream_name": "{instance_id}/yarn-containers",
                "timezone": "UTC"
              },
              {
                "file_path": "/var/log/flink/*.log",
                "log_group_name": "${local.log_group_name}",
                "log_stream_name": "{instance_id}/flink",
                "timezone": "UTC"
              },
              {
                "file_path": "/var/log/hadoop-yarn/*.log",
                "log_group_name": "${local.log_group_name}",
                "log_stream_name": "{instance_id}/yarn-daemons",
                "timezone": "UTC"
              }
            ]
          }
        }
      }
    }
    JSON

    sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
      -a fetch-config -m ec2 -s -c file:/opt/aws/amazon-cloudwatch-agent/etc/claims-flink.json \
      || log "agent start failed"
    exit 0
  SCRIPT
}

# ---------------------------------------------------------------------------
# The cluster
# ---------------------------------------------------------------------------
resource "aws_emr_cluster" "this" {
  name          = "${var.name}-flink"
  release_label = var.release_label
  applications  = var.applications
  service_role  = aws_iam_role.service.arn
  log_uri       = "s3://${var.logs_bucket_name}/"

  termination_protection            = false
  keep_job_flow_alive_when_no_steps = true

  ec2_attributes {
    subnet_id                         = var.subnet_id
    instance_profile                  = aws_iam_instance_profile.this.arn
    emr_managed_master_security_group = aws_security_group.primary.id
    emr_managed_slave_security_group  = aws_security_group.core.id
    service_access_security_group     = aws_security_group.service_access.id
  }

  master_instance_group {
    instance_type = var.primary_instance_type
  }

  core_instance_group {
    instance_type  = var.core_instance_type
    instance_count = var.core_instance_count

    ebs_config {
      size                 = var.core_ebs_size
      type                 = "gp3"
      volumes_per_instance = 1
    }
  }

  auto_termination_policy {
    idle_timeout = var.idle_timeout_seconds
  }

  bootstrap_action {
    name = "cloudwatch-agent"
    path = "s3://${var.artifacts_bucket_name}/${local.bootstrap_key}"
  }

  depends_on = [
    aws_iam_role_policy_attachment.service,
    aws_iam_role_policy.instance,
    aws_iam_role_policy_attachment.instance_managed,
    aws_s3_object.cloudwatch_bootstrap,
    aws_cloudwatch_log_group.flink,
  ]
}
