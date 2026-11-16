terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.33"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.15"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

# Pinned chart versions (all verified to exist in their repositories).
locals {
  charts = {
    aws_load_balancer_controller = "1.8.4"  # app v2.8.3, matches modules/iam/policies/alb-controller.json
    metrics_server               = "3.12.2" # app v0.7.2
    argo_cd                      = "7.9.1"  # Argo CD v2.14
    argocd_apps                  = "2.0.8"
    kube_prometheus_stack        = "65.8.1"
    loki                         = "6.55.0" # Loki 3.6, single-binary mode
    promtail                     = "6.17.1"
  }
}
