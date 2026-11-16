# Runtime configuration for the services in the `claims` namespace:
# namespace, IRSA service accounts, per-service database users and Secrets,
# shared endpoints ConfigMap, JWT keys, and the Kafka topics.

resource "kubernetes_namespace_v1" "claims" {
  metadata {
    name = "claims"
  }
}

# IRSA service accounts. Their role ARNs contain the account ID, so they are
# created here rather than in Git; manifests just set serviceAccountName.
resource "kubernetes_service_account_v1" "irsa" {
  for_each = {
    for k, v in {
      claim-intake  = local.roles.claim_intake
      ops-dashboard = local.roles.ops_dashboard
    } : k => v if v != null
  }

  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.claims.metadata[0].name
    annotations = {
      "eks.amazonaws.com/role-arn" = each.value
    }
  }
}

# ---------------------------------------------------------------------------
# Databases: one database + owner user per service
# ---------------------------------------------------------------------------
locals {
  rds_master = local.rds_enabled ? jsondecode(data.aws_secretsmanager_secret_version.rds_master[0].secret_string) : null
  rds_host   = local.infra.rds_endpoint
  rds_port   = coalesce(local.infra.rds_port, 5432)

  db_services = local.rds_enabled ? toset(var.db_services) : toset([])

  db_init_script = <<-SCRIPT
    set -eu
    echo "Waiting for $PGHOST:$PGPORT ..."
    until pg_isready -q; do sleep 5; done

    for svc in $SERVICES; do
      user="$${svc}_user"
      db="$svc"
      pw="$(printenv "PW_$svc")"

      if psql -tAc "SELECT 1 FROM pg_roles WHERE rolname = '$user'" | grep -q 1; then
        psql -v ON_ERROR_STOP=1 -qc "ALTER ROLE $user WITH LOGIN PASSWORD '$pw'"
        echo "role $user: password updated"
      else
        psql -v ON_ERROR_STOP=1 -qc "CREATE ROLE $user WITH LOGIN PASSWORD '$pw'"
        echo "role $user: created"
      fi

      # PostgreSQL 16: the master user must be a member of a role before it
      # can create a database owned by that role. Re-granting is a no-op.
      psql -v ON_ERROR_STOP=1 -qc "GRANT $user TO CURRENT_USER"

      if psql -tAc "SELECT 1 FROM pg_database WHERE datname = '$db'" | grep -q 1; then
        echo "database $db: exists"
      else
        psql -v ON_ERROR_STOP=1 -qc "CREATE DATABASE $db OWNER $user"
        echo "database $db: created"
      fi
      psql -v ON_ERROR_STOP=1 -qc "REVOKE ALL ON DATABASE $db FROM PUBLIC"
    done
    echo "db-init finished"
  SCRIPT

  kafka_topics_script = <<-SCRIPT
    set -eu
    for topic in $TOPICS; do
      /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
        --create --if-not-exists --topic "$topic" \
        --partitions "$PARTITIONS" --replication-factor "$REPLICATION"
    done
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list
  SCRIPT
}

resource "random_password" "db" {
  for_each = local.db_services

  length  = 32
  special = false # alphanumeric only: safe inside JDBC URLs, shell and SQL literals
}

# Master credentials for the db-init Job only, in their own Secret so no
# service ever mounts them.
resource "kubernetes_secret_v1" "db_init" {
  count = local.rds_enabled ? 1 : 0

  metadata {
    name      = "db-init-master"
    namespace = kubernetes_namespace_v1.claims.metadata[0].name
  }

  data = merge(
    {
      PGHOST     = local.rds_host
      PGPORT     = tostring(local.rds_port)
      PGUSER     = local.rds_master.username
      PGPASSWORD = local.rds_master.password
      PGDATABASE = "postgres"
      PGSSLMODE  = "require"
      SERVICES   = join(" ", sort(tolist(local.db_services)))
    },
    { for svc in local.db_services : "PW_${svc}" => random_password.db[svc].result },
  )
}

# Idempotent: creates missing roles/databases and resets passwords of existing roles.
resource "kubernetes_job_v1" "db_init" {
  count = local.rds_enabled ? 1 : 0

  metadata {
    name      = "db-init"
    namespace = kubernetes_namespace_v1.claims.metadata[0].name
  }

  spec {
    backoff_limit = 4

    template {
      metadata {
        labels = { app = "db-init" }
      }

      spec {
        restart_policy = "Never"

        container {
          name    = "psql"
          image   = var.postgres_image
          command = ["/bin/sh", "-c"]
          args    = [local.db_init_script]

          env_from {
            secret_ref {
              name = kubernetes_secret_v1.db_init[0].metadata[0].name
            }
          }

          resources {
            requests = { cpu = "50m", memory = "64Mi" }
            limits   = { memory = "128Mi" }
          }
        }
      }
    }
  }

  wait_for_completion = true

  timeouts {
    create = "5m"
    update = "5m"
  }
}

# One Secret per service; Spring Boot reads these env vars directly.
resource "kubernetes_secret_v1" "service_db" {
  for_each = local.db_services

  metadata {
    name      = "${each.key}-db"
    namespace = kubernetes_namespace_v1.claims.metadata[0].name
  }

  data = {
    SPRING_DATASOURCE_URL      = "jdbc:postgresql://${local.rds_host}:${local.rds_port}/${each.key}?sslmode=require"
    SPRING_DATASOURCE_USERNAME = "${each.key}_user"
    SPRING_DATASOURCE_PASSWORD = random_password.db[each.key].result
  }

  depends_on = [kubernetes_job_v1.db_init]
}

# ---------------------------------------------------------------------------
# Shared, non-secret infrastructure endpoints
# ---------------------------------------------------------------------------
resource "kubernetes_config_map_v1" "platform_endpoints" {
  metadata {
    name      = "platform-endpoints"
    namespace = kubernetes_namespace_v1.claims.metadata[0].name
  }

  data = {
    # Empty string when the component is disabled (coalesce() would fail on null + "").
    KAFKA_BOOTSTRAP   = local.msk_enabled ? local.infra.msk_bootstrap_brokers : ""
    DOCUMENTS_BUCKET  = local.infra.documents_bucket
    RESULTS_QUEUE_URL = local.infra.results_queue_url
    ANALYTICS_TABLE   = local.dynamodb_enabled ? local.infra.analytics_table_name : ""
    AWS_REGION        = var.region
  }
}

# ---------------------------------------------------------------------------
# JWT signing keys for the login service (RS256)
# ---------------------------------------------------------------------------
resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "kubernetes_secret_v1" "jwt_keys" {
  metadata {
    name      = "jwt-keys"
    namespace = kubernetes_namespace_v1.claims.metadata[0].name
  }

  data = {
    "private.pem" = tls_private_key.jwt.private_key_pem_pkcs8 # PKCS#8, what Java's KeyFactory expects
    "public.pem"  = tls_private_key.jwt.public_key_pem
  }
}

# ---------------------------------------------------------------------------
# Kafka topics (only when MSK exists)
# ---------------------------------------------------------------------------
resource "kubernetes_job_v1" "kafka_topics" {
  count = local.msk_enabled ? 1 : 0

  metadata {
    name      = "kafka-topics"
    namespace = kubernetes_namespace_v1.claims.metadata[0].name
  }

  spec {
    backoff_limit = 4

    template {
      metadata {
        labels = { app = "kafka-topics" }
      }

      spec {
        restart_policy = "Never"

        container {
          name    = "kafka-topics"
          image   = var.kafka_image
          command = ["/bin/sh", "-c"]
          args    = [local.kafka_topics_script]

          env {
            name  = "BOOTSTRAP"
            value = local.infra.msk_bootstrap_brokers
          }
          env {
            name  = "TOPICS"
            value = join(" ", var.kafka_topics)
          }
          env {
            name  = "PARTITIONS"
            value = tostring(var.kafka_topic_partitions)
          }
          env {
            name  = "REPLICATION"
            value = "2"
          }

          resources {
            requests = { cpu = "100m", memory = "256Mi" }
            limits   = { memory = "512Mi" }
          }
        }
      }
    }
  }

  wait_for_completion = true

  timeouts {
    create = "5m"
    update = "5m"
  }
}
