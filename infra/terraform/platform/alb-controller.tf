# AWS Load Balancer Controller: turns Ingress objects into ALBs (and
# LoadBalancer Services into NLBs). Installed first: it registers a mutating
# webhook for Services, so every other release waits for it to be ready.
resource "helm_release" "aws_load_balancer_controller" {
  name       = "aws-load-balancer-controller"
  repository = "https://aws.github.io/eks-charts"
  chart      = "aws-load-balancer-controller"
  version    = local.charts.aws_load_balancer_controller
  namespace  = "kube-system"
  wait       = true
  timeout    = 600

  values = [yamlencode({
    clusterName  = local.infra.cluster_name
    region       = var.region
    vpcId        = local.infra.vpc_id
    replicaCount = 1 # demo: one replica saves a pod slot; use 2 for production

    serviceAccount = {
      create = true
      name   = "aws-load-balancer-controller"
      annotations = {
        "eks.amazonaws.com/role-arn" = local.roles.alb_controller
      }
    }
  })]
}

# Metrics Server feeds CPU/memory to the HorizontalPodAutoscalers (and `kubectl top`).
resource "helm_release" "metrics_server" {
  name       = "metrics-server"
  repository = local.use_local_charts ? null : "https://kubernetes-sigs.github.io/metrics-server/"
  chart      = local.use_local_charts ? "${local.chart_dir}/metrics-server-${local.charts.metrics_server}.tgz" : "metrics-server"
  version    = local.charts.metrics_server
  namespace  = "kube-system"
  wait       = true
  timeout    = 300

  depends_on = [helm_release.aws_load_balancer_controller]
}
