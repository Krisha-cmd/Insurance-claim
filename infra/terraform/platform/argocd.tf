resource "kubernetes_namespace_v1" "argocd" {
  metadata {
    name = "argocd"
  }
}

locals {
  alb_ingress_annotations = {
    "alb.ingress.kubernetes.io/scheme"        = "internet-facing"
    "alb.ingress.kubernetes.io/target-type"   = "ip"
    "alb.ingress.kubernetes.io/listen-ports"  = "[{\"HTTP\":80}]"
    "alb.ingress.kubernetes.io/inbound-cidrs" = join(",", var.ingress_allowed_cidrs)
  }

  argocd_values = {
    configs = {
      params = {
        # TLS is terminated by kubectl port-forward / the ALB, so the server speaks plain HTTP.
        "server.insecure" = true
      }
    }
    # Not needed for the demo; saves two pods.
    dex           = { enabled = false }
    notifications = { enabled = false }
  }

  # Applied as a second values document only when expose_argocd = true.
  argocd_ingress_values = {
    # No DNS name in the demo: an empty host makes the ALB rule match any hostname.
    global = { domain = "" }
    server = {
      ingress = {
        enabled          = true
        controller       = "generic"
        ingressClassName = "alb"
        hostname         = ""
        path             = "/"
        pathType         = "Prefix"
        annotations = merge(local.alb_ingress_annotations, {
          "alb.ingress.kubernetes.io/healthcheck-path" = "/healthz"
        })
      }
    }
  }
}

resource "helm_release" "argocd" {
  name       = "argocd"
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argo-cd"
  version    = local.charts.argo_cd
  namespace  = kubernetes_namespace_v1.argocd.metadata[0].name
  wait       = true
  timeout    = 900

  values = concat(
    [yamlencode(local.argocd_values)],
    var.expose_argocd ? [yamlencode(local.argocd_ingress_values)] : [],
  )

  depends_on = [helm_release.aws_load_balancer_controller]
}
