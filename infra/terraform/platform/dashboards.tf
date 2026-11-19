# ---------------------------------------------------------------------------
# Grafana dashboard "Claims platform", loaded by the Grafana sidecar (label grafana_dashboard=1).
#
# Rows, top to bottom:
#   Overview                     total RPS, 5xx share, p95, pods ready, nodes ready, restarts
#   Services: REST               RPS, error rate and latency per service; status codes; slowest endpoints
#   Services: gRPC               service-to-service calls (including every 3PC message) per method
#   JVM and database pools       heap, CPU, Hikari connections (the first limit to run out, see capacity.md)
#   Kubernetes cluster health    nodes, requests vs allocatable, pod phases, restarts, HPAs, volumes
#   AWS                          SQS, Lambda and RDS from CloudWatch (ap-south-1)
#   Logs                         every pod through Loki; the Flink job and the Lambda through CloudWatch Logs
#
# Panels are written as short specs below and expanded into Grafana's JSON model.
# Sources: Spring Boot/Micrometer (/actuator/prometheus via the claims-services ServiceMonitor),
# kube-state-metrics, node-exporter and kubelet (kube-prometheus-stack), Promtail → Loki, and CloudWatch.
# ---------------------------------------------------------------------------

locals {
  ds = {
    prom = { type = "prometheus", uid = "prometheus" }
    loki = { type = "loki", uid = "loki" }
    cw   = { type = "cloudwatch", uid = "cloudwatch" }
  }
  ref_ids = ["A", "B", "C", "D", "E", "F"]

  # Selectors reused by many queries.
  http = "namespace=\"$namespace\", service=~\"$service\""
  pods = "namespace=\"$namespace\", container=~\"$service\""

  # AWS names from the infra stack (null when that component is disabled, or the state predates the output).
  queue_urls = compact([
    try(local.infra.upload_events_queue_url, null),
    try(local.infra.results_queue_url, null),
    try(local.infra.upload_events_dlq_url, null),
    try(local.infra.results_dlq_url, null),
  ])
  queue_names     = [for u in local.queue_urls : regex("[^/]+$", u)]
  lambda_function = try(local.infra.lambda_function_name, null)
  lambda_logs     = try(local.infra.lambda_log_group, null)
  flink_logs      = try(local.infra.emr_flink_log_group, null)
  rds_instance    = try(local.infra.rds_instance_id, null)

  # ---- rows: [title, y]
  row_specs = [
    ["Overview", 0],
    ["Services: REST (HTTP)", 5],
    ["Services: gRPC (service-to-service, including the 3PC)", 30],
    ["JVM and database pools", 39],
    ["Kubernetes cluster health", 48],
    ["AWS (CloudWatch, ${var.region})", 73],
    ["Logs", 90],
  ]

  # ---- stat tiles: title, unit, expr, steps = [[color, from-value], ...] (the first from-value is null)
  stat_specs = [
    {
      title = "Requests / s", unit = "reqps", x = 0
      expr  = "sum(rate(http_server_requests_seconds_count{${local.http}}[5m]))"
      steps = [["blue", null]]
    },
    {
      title = "Error rate (5xx)", unit = "percentunit", x = 4
      expr  = "(sum(rate(http_server_requests_seconds_count{${local.http}, status=~\"5..\"}[5m])) or vector(0)) / sum(rate(http_server_requests_seconds_count{${local.http}}[5m]))"
      steps = [["green", null], ["orange", 0.01], ["red", 0.05]]
    },
    {
      title = "p95 latency", unit = "s", x = 8
      expr  = "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{${local.http}}[5m])))"
      steps = [["green", null], ["orange", 0.5], ["red", 1]]
    },
    {
      title = "Pods ready", unit = "percentunit", x = 12
      expr  = "sum(kube_pod_status_ready{namespace=\"$namespace\", condition=\"true\"}) / count(kube_pod_status_ready{namespace=\"$namespace\", condition=\"true\"})"
      steps = [["red", null], ["orange", 0.9], ["green", 1]]
    },
    {
      title = "Nodes ready", unit = "short", x = 16
      expr  = "sum(kube_node_status_condition{condition=\"Ready\", status=\"true\"})"
      steps = [["red", null], ["green", 1]]
    },
    {
      title = "Container restarts (1 h)", unit = "short", x = 20
      expr  = "sum(increase(kube_pod_container_status_restarts_total{namespace=\"$namespace\"}[1h]))"
      steps = [["green", null], ["orange", 1], ["red", 5]]
    },
  ]

  # ---- time series: title, unit, x, y, w, h, ds, q = [[expr, legend], ...], desc
  ts_specs = [
    # REST
    {
      title = "Requests per second, by service", unit = "reqps", x = 0, y = 6, w = 12, h = 8, ds = "prom"
      desc  = "Incoming REST requests (all status codes), per Kubernetes Service."
      q     = [["sum by (service) (rate(http_server_requests_seconds_count{${local.http}}[1m]))", "{{service}}"]]
    },
    {
      title = "Error rate (5xx share), by service", unit = "percentunit", max = 1, x = 12, y = 6, w = 12, h = 8, ds = "prom"
      desc  = "Share of requests answered with a 5xx. 4xx (bad input, 404, 409) are the caller's errors and are not counted."
      q     = [["(sum by (service) (rate(http_server_requests_seconds_count{${local.http}, status=~\"5..\"}[5m])) or 0 * sum by (service) (rate(http_server_requests_seconds_count{${local.http}}[5m]))) / sum by (service) (rate(http_server_requests_seconds_count{${local.http}}[5m]))", "{{service}}"]]
    },
    {
      title = "p95 latency, by service", unit = "s", x = 0, y = 14, w = 12, h = 8, ds = "prom"
      desc  = "95th percentile of REST response time, from the Micrometer histogram buckets."
      q     = [["histogram_quantile(0.95, sum by (service, le) (rate(http_server_requests_seconds_bucket{${local.http}}[5m])))", "{{service}}"]]
    },
    {
      title = "Latency percentiles (selected services)", unit = "s", x = 12, y = 14, w = 12, h = 8, ds = "prom"
      desc  = "p50, p95 and p99 across the services picked in the Service filter."
      q = [
        ["histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{${local.http}}[5m])))", "p50"],
        ["histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{${local.http}}[5m])))", "p95"],
        ["histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{${local.http}}[5m])))", "p99"],
      ]
    },
    {
      title = "Requests by status code", unit = "reqps", x = 0, y = 22, w = 12, h = 8, ds = "prom"
      desc  = "Every REST response, split by HTTP status."
      q     = [["sum by (status) (rate(http_server_requests_seconds_count{${local.http}}[1m]))", "{{status}}"]]
    },
    # gRPC
    {
      title = "gRPC calls per second, by method", unit = "reqps", x = 0, y = 31, w = 8, h = 8, ds = "prom"
      desc  = "Calls received by each gRPC server (Policy, Claim Intake, Eligibility, Assessment, Payment), including CanCommit / PreCommit / DoCommit / Abort."
      q     = [["sum by (service, method) (rate(grpc_server_processing_duration_seconds_count{${local.http}}[1m]))", "{{service}} {{method}}"]]
    },
    {
      title = "gRPC errors per second (status ≠ OK)", unit = "reqps", x = 8, y = 31, w = 8, h = 8, ds = "prom"
      desc  = "Failed gRPC calls by status code, e.g. NOT_FOUND, FAILED_PRECONDITION, DEADLINE_EXCEEDED."
      q     = [["sum by (service, method, statusCode) (rate(grpc_server_processing_duration_seconds_count{${local.http}, statusCode!=\"OK\"}[5m]))", "{{service}} {{method}} {{statusCode}}"]]
    },
    {
      title = "gRPC p95 latency, by method", unit = "s", x = 16, y = 31, w = 8, h = 8, ds = "prom"
      desc  = "Each 3PC phase has a 5 s deadline; a p95 close to 5 s means votes are about to time out."
      q     = [["histogram_quantile(0.95, sum by (service, method, le) (rate(grpc_server_processing_duration_seconds_bucket{${local.http}}[5m])))", "{{service}} {{method}}"]]
    },
    # JVM and pools
    {
      title = "JVM heap used, by pod", unit = "bytes", x = 0, y = 40, w = 8, h = 8, ds = "prom"
      desc  = "Heap in use. The container limit is 768 Mi for the JVM services."
      q     = [["sum by (pod) (jvm_memory_used_bytes{${local.http}, area=\"heap\"})", "{{pod}}"]]
    },
    {
      title = "Process CPU, by pod", unit = "percentunit", x = 8, y = 40, w = 8, h = 8, ds = "prom"
      desc  = "JVM process CPU as a share of the CPUs it can see."
      q     = [["max by (pod) (process_cpu_usage{${local.http}})", "{{pod}}"]]
    },
    {
      title = "Database connections (Hikari)", unit = "short", x = 16, y = 40, w = 8, h = 8, ds = "prom"
      desc  = "Connections open per service, and threads waiting for one. Every replica opens its own pool, so this grows with the HPA (capacity.md, limit 1)."
      q = [
        ["sum by (service) (hikaricp_connections{${local.http}})", "{{service}} open"],
        ["sum by (service) (hikaricp_connections_pending{${local.http}}) > 0", "{{service}} waiting"],
      ]
    },
    # Cluster health
    {
      title = "Node CPU used", unit = "percentunit", max = 1, x = 0, y = 49, w = 8, h = 8, ds = "prom"
      desc  = "Per node, from node-exporter."
      q     = [["1 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))", "{{instance}}"]]
    },
    {
      title = "Node memory used", unit = "percentunit", max = 1, x = 8, y = 49, w = 8, h = 8, ds = "prom"
      desc  = "Per node, from node-exporter."
      q     = [["1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes", "{{instance}}"]]
    },
    {
      title = "CPU: requested vs allocatable (cluster)", unit = "short", x = 16, y = 49, w = 8, h = 8, ds = "prom"
      desc  = "In cores. When requested reaches allocatable, new pods (and HPA scale-outs) stay Pending."
      q = [
        ["sum(kube_pod_container_resource_requests{resource=\"cpu\"})", "requested"],
        ["sum(kube_node_status_allocatable{resource=\"cpu\"})", "allocatable"],
      ]
    },
    {
      title = "Memory: requested vs allocatable (cluster)", unit = "bytes", x = 0, y = 57, w = 8, h = 8, ds = "prom"
      desc  = "When requested reaches allocatable, new pods stay Pending."
      q = [
        ["sum(kube_pod_container_resource_requests{resource=\"memory\"})", "requested"],
        ["sum(kube_node_status_allocatable{resource=\"memory\"})", "allocatable"],
      ]
    },
    {
      title = "Pods by phase (all namespaces)", unit = "short", x = 8, y = 57, w = 8, h = 8, ds = "prom"
      desc  = "Anything in Pending or Failed for long needs a look (kubectl describe pod)."
      q     = [["sum by (phase) (kube_pod_status_phase) > 0", "{{phase}}"]]
    },
    {
      title = "Container restarts (15 min), by pod", unit = "short", x = 16, y = 57, w = 8, h = 8, ds = "prom"
      desc  = "Crash loops and OOM kills show up here. All namespaces."
      q     = [["sum by (namespace, pod) (increase(kube_pod_container_status_restarts_total[15m])) > 0", "{{namespace}}/{{pod}}"]]
    },
    {
      title = "HPA replicas: current and desired", unit = "short", x = 0, y = 65, w = 12, h = 8, ds = "prom"
      desc  = "Desired above current means the HPA wants pods that cannot be scheduled (or are starting)."
      q = [
        ["kube_horizontalpodautoscaler_status_current_replicas{namespace=\"$namespace\"}", "{{horizontalpodautoscaler}} current"],
        ["kube_horizontalpodautoscaler_status_desired_replicas{namespace=\"$namespace\"}", "{{horizontalpodautoscaler}} desired"],
      ]
    },
    {
      title = "Persistent volume usage", unit = "percentunit", max = 1, x = 12, y = 65, w = 12, h = 8, ds = "prom"
      desc  = "Prometheus and Loki keep their data on gp3 volumes."
      q     = [["max by (namespace, persistentvolumeclaim) (kubelet_volume_stats_used_bytes / kubelet_volume_stats_capacity_bytes)", "{{namespace}}/{{persistentvolumeclaim}}"]]
    },
    # Logs
    {
      title = "Error log lines per minute, by service", unit = "short", x = 0, y = 91, w = 24, h = 6, ds = "loki"
      desc  = "Lines containing ERROR or an exception, counted by Loki from every pod's output."
      q     = [["sum by (container) (count_over_time({${local.pods}} |~ \"ERROR|Exception\" [1m]))", "{{container}}"]]
    },
  ]

  # ---- CloudWatch metric panels: title, unit, x, y, w, metrics = [{ns, metric, dims, stat, label}]
  cw_specs = concat(
    length(local.queue_names) == 0 ? [] : [{
      title = "SQS: messages waiting", unit = "short", x = 0, y = 74, w = 8
      desc  = "Documents waiting for the Lambda, results waiting for Claim Intake, and anything parked in a dead-letter queue."
      metrics = [for n in local.queue_names : {
        ns = "AWS/SQS", metric = "ApproximateNumberOfMessagesVisible", dims = { QueueName = n }, stat = "Maximum", label = n
      }]
    }],
    local.lambda_function == null ? [] : [
      {
        title = "Lambda: invocations and errors", unit = "short", x = 8, y = 74, w = 8
        desc  = "Document Verification. Errors are infrastructure failures; a rejected document is not an error."
        metrics = [
          { ns = "AWS/Lambda", metric = "Invocations", dims = { FunctionName = local.lambda_function }, stat = "Sum", label = "invocations" },
          { ns = "AWS/Lambda", metric = "Errors", dims = { FunctionName = local.lambda_function }, stat = "Sum", label = "errors" },
          { ns = "AWS/Lambda", metric = "Throttles", dims = { FunctionName = local.lambda_function }, stat = "Sum", label = "throttles" },
        ]
      },
      {
        title = "Lambda: duration", unit = "ms", x = 16, y = 74, w = 8
        desc  = "Average and maximum time per batch."
        metrics = [
          { ns = "AWS/Lambda", metric = "Duration", dims = { FunctionName = local.lambda_function }, stat = "Average", label = "average" },
          { ns = "AWS/Lambda", metric = "Duration", dims = { FunctionName = local.lambda_function }, stat = "Maximum", label = "maximum" },
        ]
      },
    ],
    local.rds_instance == null ? [] : [
      {
        title   = "RDS: database connections", unit = "short", x = 0, y = 82, w = 8
        desc    = "All services share one instance; db.t4g.micro allows roughly 80–110 (capacity.md)."
        metrics = [{ ns = "AWS/RDS", metric = "DatabaseConnections", dims = { DBInstanceIdentifier = local.rds_instance }, stat = "Maximum", label = "connections" }]
      },
      {
        title   = "RDS: CPU", unit = "percent", x = 8, y = 82, w = 8
        desc    = "Burstable instance: sustained high CPU spends credits."
        metrics = [{ ns = "AWS/RDS", metric = "CPUUtilization", dims = { DBInstanceIdentifier = local.rds_instance }, stat = "Average", label = "CPU" }]
      },
      {
        title   = "RDS: freeable memory", unit = "bytes", x = 16, y = 82, w = 8
        desc    = "1 GiB in total on db.t4g.micro."
        metrics = [{ ns = "AWS/RDS", metric = "FreeableMemory", dims = { DBInstanceIdentifier = local.rds_instance }, stat = "Minimum", label = "free" }]
      },
    ],
  )

  # ---- log panels
  cw_log_specs = concat(
    local.flink_logs == null ? [] : [{ title = "Audit Flink job (EMR) logs", group = local.flink_logs, x = 0 }],
    local.lambda_logs == null ? [] : [{ title = "Document Verification (Lambda) logs", group = local.lambda_logs, x = 12 }],
  )

  # ---------------------------------------------------------------- expansion into Grafana JSON
  row_panels = [for i, r in local.row_specs : {
    id      = 1000 + i, type = "row", title = r[0], collapsed = false, panels = []
    gridPos = { h = 1, w = 24, x = 0, y = r[1] }
  }]

  stat_panels = [for i, s in local.stat_specs : {
    id      = 100 + i, type = "stat", title = s.title, datasource = local.ds.prom
    gridPos = { h = 4, w = 4, x = s.x, y = 1 }
    fieldConfig = {
      defaults = {
        unit       = s.unit
        decimals   = s.unit == "short" ? 0 : null
        color      = { mode = "thresholds" }
        thresholds = { mode = "absolute", steps = [for st in s.steps : { color = st[0], value = st[1] }] }
      }
      overrides = []
    }
    options = {
      reduceOptions = { calcs = ["lastNotNull"], fields = "", values = false }
      colorMode     = "value"
      graphMode     = "area"
      textMode      = "auto"
      justifyMode   = "auto"
    }
    targets = [{ refId = "A", datasource = local.ds.prom, expr = s.expr }]
  }]

  ts_panels = [for i, p in local.ts_specs : {
    id      = 200 + i, type = "timeseries", title = p.title, description = p.desc, datasource = local.ds[p.ds]
    gridPos = { h = p.h, w = p.w, x = p.x, y = p.y }
    fieldConfig = {
      defaults = {
        unit   = p.unit
        min    = 0
        max    = try(p.max, null) # shares (0..1): an all-zero series would otherwise get a 0..100 axis
        custom = { lineWidth = 2, fillOpacity = 0, showPoints = "never", spanNulls = true }
      }
      overrides = []
    }
    options = {
      legend  = { displayMode = "table", placement = "bottom", calcs = ["mean", "max", "lastNotNull"] }
      tooltip = { mode = "multi", sort = "desc" }
    }
    targets = [for j, t in p.q : { refId = local.ref_ids[j], datasource = local.ds[p.ds], expr = t[0], legendFormat = t[1] }]
  }]

  slowest_endpoints_panel = {
    id          = 300, type = "bargauge", title = "Slowest endpoints (p95, last 15 min)", datasource = local.ds.prom
    description = "The ten REST endpoints with the highest p95 over the last 15 minutes."
    gridPos     = { h = 8, w = 12, x = 12, y = 22 }
    fieldConfig = {
      defaults = {
        unit       = "s"
        min        = 0
        color      = { mode = "thresholds" }
        thresholds = { mode = "absolute", steps = [{ color = "green", value = null }, { color = "orange", value = 0.5 }, { color = "red", value = 1 }] }
      }
      overrides = []
    }
    options = {
      orientation   = "horizontal"
      displayMode   = "basic"
      showUnfilled  = true
      reduceOptions = { calcs = ["lastNotNull"], fields = "", values = false }
    }
    targets = [{
      refId        = "A"
      datasource   = local.ds.prom
      instant      = true
      expr         = "topk(10, histogram_quantile(0.95, sum by (service, method, uri, le) (rate(http_server_requests_seconds_bucket{${local.http}, uri!~\"/actuator.*\"}[15m]))))"
      legendFormat = "{{service}} {{method}} {{uri}}"
    }]
  }

  cw_panels = [for i, p in local.cw_specs : {
    id      = 400 + i, type = "timeseries", title = p.title, description = p.desc, datasource = local.ds.cw
    gridPos = { h = 8, w = p.w, x = p.x, y = p.y }
    fieldConfig = {
      defaults  = { unit = p.unit, min = 0, custom = { lineWidth = 2, fillOpacity = 0, showPoints = "never", spanNulls = true } }
      overrides = []
    }
    options = {
      legend  = { displayMode = "table", placement = "bottom", calcs = ["mean", "max", "lastNotNull"] }
      tooltip = { mode = "multi", sort = "desc" }
    }
    targets = [for j, m in p.metrics : {
      refId            = local.ref_ids[j]
      datasource       = local.ds.cw
      queryMode        = "Metrics"
      region           = "default"
      namespace        = m.ns
      metricName       = m.metric
      dimensions       = m.dims
      statistic        = m.stat
      period           = ""
      matchExact       = true
      metricQueryType  = 0
      metricEditorMode = 0
      id               = ""
      expression       = ""
      label            = m.label
    }]
  }]

  loki_log_panel = {
    id          = 500, type = "logs", title = "Service logs (Loki)", datasource = local.ds.loki
    description = "Every pod in the namespace, filtered by the Service and Log search boxes at the top."
    gridPos     = { h = 12, w = 24, x = 0, y = 97 }
    options     = { showTime = true, wrapLogMessage = true, sortOrder = "Descending", enableLogDetails = true }
    targets     = [{ refId = "A", datasource = local.ds.loki, expr = "{${local.pods}} |~ \"$search\"" }]
  }

  cw_log_panels = [for i, p in local.cw_log_specs : {
    id      = 510 + i, type = "logs", title = p.title, datasource = local.ds.cw
    gridPos = { h = 10, w = 12, x = p.x, y = 109 }
    options = { showTime = true, wrapLogMessage = true, sortOrder = "Descending" }
    targets = [{
      refId         = "A"
      datasource    = local.ds.cw
      queryMode     = "Logs"
      region        = "default"
      logGroupNames = [p.group]
      expression    = "fields @timestamp, @logStream, @message | filter @message like /$search/ | sort @timestamp desc | limit 200"
    }]
  }]

  claims_dashboard = {
    uid           = "claims-platform"
    title         = "Claims platform"
    description   = "Service metrics (RPS, errors, latency), Kubernetes cluster health, AWS services and logs."
    tags          = ["claims"]
    schemaVersion = 39
    refresh       = "30s"
    time          = { from = "now-1h", to = "now" }
    timezone      = "browser"
    templating = {
      list = [
        {
          name    = "namespace"
          label   = "Namespace"
          type    = "custom"
          query   = "claims"
          current = { text = "claims", value = "claims" }
          options = [{ text = "claims", value = "claims", selected = true }]
        },
        {
          name       = "service"
          label      = "Service"
          type       = "query"
          datasource = local.ds.prom
          definition = "label_values(up{namespace=\"$namespace\"}, service)"
          query      = { query = "label_values(up{namespace=\"$namespace\"}, service)", refId = "service" }
          refresh    = 2
          sort       = 1
          multi      = true
          includeAll = true
          allValue   = ".*"
          current    = { text = ["All"], value = ["$__all"] }
        },
        {
          name    = "search"
          label   = "Log search (regex)"
          type    = "textbox"
          query   = ""
          current = { text = "", value = "" }
        },
      ]
    }
    panels = concat(local.row_panels, local.stat_panels, local.ts_panels, [local.slowest_endpoints_panel],
    local.cw_panels, [local.loki_log_panel], local.cw_log_panels)
  }
}

resource "kubernetes_config_map_v1" "claims_dashboard" {
  metadata {
    name      = "claims-platform-dashboard"
    namespace = kubernetes_namespace_v1.observability.metadata[0].name
    labels = {
      grafana_dashboard = "1"
    }
  }

  data = {
    "claims-platform.json" = jsonencode(local.claims_dashboard)
  }
}

output "grafana_dashboard_json" {
  description = "The Claims platform dashboard as Grafana JSON (for import into another Grafana)."
  value       = jsonencode(local.claims_dashboard)
}
