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
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
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
  repository = "https://grafana.github.io/helm-charts"
  chart      = "loki"
  version    = local.charts.loki
  namespace  = kubernetes_namespace_v1.observability.metadata[0].name
  wait       = true
  timeout    = 600

  values = [yamlencode(local.loki_values)]

  depends_on = [helm_release.aws_load_balancer_controller]
}

resource "helm_release" "promtail" {
  name       = "promtail"
  repository = "https://grafana.github.io/helm-charts"
  chart      = "promtail"
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

# ---------------------------------------------------------------------------
# "Claims overview" dashboard, loaded by the Grafana sidecar (label grafana_dashboard=1)
# ---------------------------------------------------------------------------
locals {
  prom = { type = "prometheus", uid = "prometheus" }

  # [title, unit, expr, legend]
  metric_panels = [
    ["Requests per second", "reqps", "sum by (service) (rate(http_server_requests_seconds_count{namespace=\"$namespace\"}[1m]))", "{{service}}"],
    ["Error rate (5xx share)", "percentunit", "sum by (service) (rate(http_server_requests_seconds_count{namespace=\"$namespace\", status=~\"5..\"}[5m])) / sum by (service) (rate(http_server_requests_seconds_count{namespace=\"$namespace\"}[5m]))", "{{service}}"],
    ["p95 latency", "s", "histogram_quantile(0.95, sum by (service, le) (rate(http_server_requests_seconds_bucket{namespace=\"$namespace\"}[5m])))", "{{service}}"],
    ["HPA replicas (current)", "short", "kube_horizontalpodautoscaler_status_current_replicas{namespace=\"$namespace\"}", "{{horizontalpodautoscaler}}"],
    ["Node CPU", "percentunit", "1 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))", "{{instance}}"],
    ["Node memory", "percentunit", "1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes", "{{instance}}"],
    ["Pod CPU (cores)", "short", "sum by (pod) (rate(container_cpu_usage_seconds_total{namespace=\"$namespace\", container!=\"\"}[5m]))", "{{pod}}"],
    ["Pod memory (working set)", "bytes", "sum by (pod) (container_memory_working_set_bytes{namespace=\"$namespace\", container!=\"\"})", "{{pod}}"],
  ]

  timeseries_panels = [
    for i, p in local.metric_panels : {
      id         = i + 1
      type       = "timeseries"
      title      = p[0]
      datasource = local.prom
      gridPos    = { h = 8, w = 12, x = (i % 2) * 12, y = floor(i / 2) * 8 }
      fieldConfig = {
        defaults  = { unit = p[1], custom = { lineWidth = 2, fillOpacity = 0 } }
        overrides = []
      }
      options = {
        legend  = { displayMode = "list", placement = "bottom" }
        tooltip = { mode = "multi", sort = "desc" }
      }
      targets = [{ refId = "A", datasource = local.prom, expr = p[2], legendFormat = p[3] }]
    }
  ]

  log_y = ceil(length(local.metric_panels) / 2) * 8

  log_panels = concat(
    [{
      id         = 100
      type       = "logs"
      title      = "Service logs (Loki)"
      datasource = { type = "loki", uid = "loki" }
      gridPos    = { h = 10, w = 24, x = 0, y = local.log_y }
      options    = { showTime = true, wrapLogMessage = true, sortOrder = "Descending" }
      targets    = [{ refId = "A", datasource = { type = "loki", uid = "loki" }, expr = "{namespace=\"$namespace\"}" }]
    }],
    local.infra.emr_flink_log_group == null ? [] : [{
      id         = 101
      type       = "logs"
      title      = "Flink job logs (EMR, CloudWatch)"
      datasource = { type = "cloudwatch", uid = "cloudwatch" }
      gridPos    = { h = 10, w = 24, x = 0, y = local.log_y + 10 }
      options    = { showTime = true, wrapLogMessage = true, sortOrder = "Descending" }
      targets = [{
        refId         = "A"
        datasource    = { type = "cloudwatch", uid = "cloudwatch" }
        queryMode     = "Logs"
        region        = "default"
        logGroupNames = [local.infra.emr_flink_log_group]
        expression    = "fields @timestamp, @logStream, @message | sort @timestamp desc | limit 200"
      }]
    }],
  )

  claims_dashboard = {
    uid           = "claims-overview"
    title         = "Claims overview"
    tags          = ["claims"]
    schemaVersion = 39
    refresh       = "30s"
    time          = { from = "now-1h", to = "now" }
    templating = {
      list = [{
        name    = "namespace"
        label   = "Namespace"
        type    = "custom"
        query   = "claims"
        current = { text = "claims", value = "claims" }
        options = [{ text = "claims", value = "claims", selected = true }]
      }]
    }
    panels = concat(local.timeseries_panels, local.log_panels)
  }
}

resource "kubernetes_config_map_v1" "claims_dashboard" {
  metadata {
    name      = "claims-overview"
    namespace = kubernetes_namespace_v1.observability.metadata[0].name
    labels = {
      grafana_dashboard = "1"
    }
  }

  data = {
    "claims-overview.json" = jsonencode(local.claims_dashboard)
  }
}
