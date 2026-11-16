resource "aws_security_group" "this" {
  name        = "${var.name}-msk"
  description = "Kafka brokers: clients from EKS nodes and EMR"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name}-msk" }
}

locals {
  # One rule per (client SG, port): 9092 = plaintext, 9094 = TLS.
  ingress = merge([
    for label, sg in var.allowed_security_groups : {
      for port in [9092, 9094] : "${label}-${port}" => { sg = sg, port = port, label = label }
    }
  ]...)
}

resource "aws_vpc_security_group_ingress_rule" "kafka" {
  for_each = local.ingress

  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value.sg
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = "Kafka ${each.value.port} from ${each.value.label}"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
  description       = "Broker replication and AWS APIs"
}

resource "aws_msk_configuration" "this" {
  name           = "${var.name}-msk"
  kafka_versions = [var.kafka_version]

  server_properties = <<-PROPERTIES
    auto.create.topics.enable=true
    default.replication.factor=${min(2, var.broker_count)}
    min.insync.replicas=1
    num.partitions=3
  PROPERTIES
}

resource "aws_cloudwatch_log_group" "broker" {
  name              = "/aws/msk/${var.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name}-msk"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.this.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_size
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  # Demo setting: no client auth, plaintext (9092) and TLS (9094) both allowed inside the VPC.
  # See the README section "Switching MSK to IAM auth" for the production variant.
  client_authentication {
    unauthenticated = true
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS_PLAINTEXT"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker.name
      }
    }
  }
}
