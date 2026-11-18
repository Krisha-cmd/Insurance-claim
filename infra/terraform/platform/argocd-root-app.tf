# Private repository credentials (only when gitops_repo_private = true).
# ArgoCD picks up any Secret labelled secret-type=repository.
resource "kubernetes_secret_v1" "gitops_repo" {
  count = var.gitops_repo_private && var.gitops_repo_url != "" ? 1 : 0

  metadata {
    name      = "gitops-repo"
    namespace = kubernetes_namespace_v1.argocd.metadata[0].name
    labels = {
      "argocd.argoproj.io/secret-type" = "repository"
    }
  }

  data = {
    type     = "git"
    url      = var.gitops_repo_url
    username = var.gitops_repo_username
    password = var.gitops_repo_token
  }

  lifecycle {
    precondition {
      condition     = var.gitops_repo_token != ""
      error_message = "gitops_repo_private = true needs a token: export TF_VAR_gitops_repo_token=<token> and re-run."
    }
  }

  depends_on = [helm_release.argocd]
}

# The AppProject and the root "app of apps", read from gitops/argocd/ so Git holds the one definition
# (gitops/README.md bootstraps the same files with kubectl when Terraform is not used). They are created
# through the argocd-apps Helm chart rather than kubernetes_manifest, because kubernetes_manifest needs
# the Application CRD to exist at plan time (it does not on a fresh cluster).
locals {
  gitops_dir   = "${path.module}/../../../gitops/argocd"
  argo_project = yamldecode(file("${local.gitops_dir}/project.yaml"))
  argo_root    = yamldecode(file("${local.gitops_dir}/root-app.yaml"))
}

resource "helm_release" "argocd_root_app" {
  count = var.gitops_repo_url != "" ? 1 : 0

  name       = "argocd-root-app"
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argocd-apps"
  version    = local.charts.argocd_apps
  namespace  = kubernetes_namespace_v1.argocd.metadata[0].name

  values = [yamlencode({
    projects = {
      (local.argo_project.metadata.name) = merge(local.argo_project.spec, {
        namespace   = local.argo_project.metadata.namespace
        sourceRepos = distinct(concat(local.argo_project.spec.sourceRepos, [var.gitops_repo_url]))
      })
    }
    applications = {
      (local.argo_root.metadata.name) = merge(local.argo_root.spec, {
        namespace = local.argo_root.metadata.namespace
        source = merge(local.argo_root.spec.source, {
          repoURL        = var.gitops_repo_url
          targetRevision = var.gitops_revision
          path           = var.gitops_path
          # One environment per cluster: gitops/apps/<env>.yaml.
          directory = merge(local.argo_root.spec.source.directory, { include = "${var.env}.yaml" })
        })
      })
    }
  })]

  depends_on = [helm_release.argocd, kubernetes_secret_v1.gitops_repo]
}
