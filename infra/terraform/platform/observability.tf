# Metrics: kube-prometheus-stack (Prometheus Operator, Prometheus, Grafana,
# kube-state-metrics, node-exporter).
# Logs:    Loki (single binary, filesystem on a PVC) + Promtail on every node.
# AWS:     Grafana's CloudWatch datasource (IRSA) for EMR/Flink and Lambda logs
#          and MSK/SQS/RDS metrics.

resource "kubernetes_namespace_v1" "observability" {
  metadata {
    name = "observability"
  }
}

resource "random_password" "grafana_admin" {
  length  = 24
  special = false
}

locals {
  loki_url = "http://loki.observability.svc.cluster.local:3100"

  kube_prometheus_values = {
    # Alertmanager is not used in the demo; it would only take a pod and a PVC.
    alertmanager = { enabled = false }

    # The EKS control plane is managed by AWS and cannot be scraped.
    kubeControllerManager = { enabled = false }
    kubeScheduler         = { enabled = false }
    kubeEtcd              = { enabled = false }
    kubeProxy             = { enabled = false }

    prometheus = {
      prometheusSpec = {
        retention = var.prometheus_retention

        # Pick up every ServiceMonitor / PodMonitor / rule in any namespace,
        # not only those labelled for this Helm release.
        serviceMonitorSelectorNilUsesHelmValues = false
        podMonitorSelectorNilUsesHelmValues     = false
        ruleSelectorNilUsesHelmValues           = false

        storageSpec = {
          volumeClaimTemplate = {
            spec = {
              storageClassName = "gp3"
              accessModes      = ["ReadWriteOnce"]
              resources        = { requests = { storage = var.prometheus_storage_size } }
            }
          }
        }
      }
    }

    grafana = {
      serviceAccount = {
        create = true
        name   = "grafana" # must match the IRSA trust policy in modules/iam
        annotations = {
          "eks.amazonaws.com/role-arn" = local.roles.grafana
        }
      }

      # Prometheus is added by the chart as the default datasource (uid "prometheus").
      additionalDataSources = [
        {
          name   = "Loki"
          type   = "loki"
          uid    = "loki"
          access = "proxy"
          url    = local.loki_url
        },
        {
          name = "CloudWatch"
          type = "cloudwatch"
          uid  = "cloudwatch"
          jsonData = {
            authType      = "default" # AWS SDK default chain = IRSA web identity token
            defaultRegion = var.region
          }
        },
      ]

      sidecar = {
        dashboards = {
          enabled         = true
          label           = "grafana_dashboard"
          labelValue      = "1"
          searchNamespace = "ALL"
        }
      }
    }
  }

  # Applied as a second values document only when expose_grafana = true.
  grafana_ingress_values = {
    grafana = {
      ingress = {
        enabled          = true
        ingressClassName = "alb"
        hosts            = [] # no DNS name in the demo: match any host
        path             = "/"
        pathType         = "Prefix"
        annotations = merge(local.alb_ingress_annotations, {
          "alb.ingress.kubernetes.io/healthcheck-path" = "/api/health"
        })
      }
    }
  }

  loki_values = {
    deploymentMode = "SingleBinary"

    loki = {
      auth_enabled = false
      commonConfig = { replication_factor = 1 }
      storage      = { type = "filesystem" }
      schemaConfig = {
        configs = [{
          from         = "2024-04-01"
          store        = "tsdb"
          object_store = "filesystem"
          schema       = "v13"
          index        = { prefix = "loki_index_", period = "24h" }
        }]
      }
      limits_config = {
        retention_period = "168h" # 7 days
      }
      compactor = {
        retention_enabled    = true
        delete_request_store = "filesystem"
      }
    }

    singleBinary = {
      replicas = 1
      persistence = {
        enabled      = true
        size         = var.loki_storage_size
        storageClass = "gp3"
      }
    }

    # Simple-scalable components and extras are switched off in single-binary mode.
    read         = { replicas = 0 }
    write        = { replicas = 0 }
    backend      = { replicas = 0 }
    gateway      = { enabled = false }
    chunksCache  = { enabled = false }
    resultsCache = { enabled = false }
    lokiCanary   = { enabled = false }
    test         = { enabled = false }
    minio        = { enabled = false }
  }
}

resource "helm_release" "kube_prometheus_stack" {
  name       = "kube-prometheus-stack"
  repository = local.use_local_charts ? null : "https://prometheus-community.github.io/helm-charts"
  chart      = local.use_local_charts ? "${local.chart_dir}/kube-prometheus-stack-${local.charts.kube_prometheus_stack}.tgz" : "kube-prometheus-stack"
  version    = local.charts.kube_prometheus_stack
  namespace  = kubernetes_namespace_v1.observability.metadata[0].name
  wait       = true
  timeout    = 900

  values = concat(
    [yamlencode(local.kube_prometheus_values)],
    var.expose_grafana ? [yamlencode(local.grafana_ingress_values)] : [],
  )

  set_sensitive {
    name  = "grafana.adminPassword"
    value = random_password.grafana_admin.result
  }

  depends_on = [helm_release.aws_load_balancer_controller]
}

resource "helm_release" "loki" {
  name       = "loki"
  repository = local.use_local_charts ? null : "https://grafana.github.io/helm-charts"
  chart      = local.use_local_charts ? "${local.chart_dir}/loki-${local.charts.loki}.tgz" : "loki"
  version    = local.charts.loki
  namespace  = kubernetes_namespace_v1.observability.metadata[0].name
  wait       = true
  timeout    = 600

  values = [yamlencode(local.loki_values)]

  depends_on = [helm_release.aws_load_balancer_controller]
}

resource "helm_release" "promtail" {
  name       = "promtail"
  repository = local.use_local_charts ? null : "https://grafana.github.io/helm-charts"
  chart      = local.use_local_charts ? "${local.chart_dir}/promtail-${local.charts.promtail}.tgz" : "promtail"
  version    = local.charts.promtail
  namespace  = kubernetes_namespace_v1.observability.metadata[0].name
  wait       = true
  timeout    = 300

  values = [yamlencode({
    config = {
      clients = [{ url = "${local.loki_url}/loki/api/v1/push" }]
    }
  })]

  depends_on = [helm_release.loki]
}

# The Grafana dashboard is in dashboards.tf.
