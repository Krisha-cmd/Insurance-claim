output "argocd_port_forward" {
  description = "Open the ArgoCD UI at http://localhost:8080 (user: admin)."
  value       = "kubectl -n argocd port-forward svc/argocd-server 8080:80"
}

output "argocd_admin_password_command" {
  description = "Prints the initial ArgoCD admin password."
  value       = "kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo"
}

output "argocd_url_command" {
  description = "Prints the ArgoCD ALB hostname (only when expose_argocd = true; takes ~2 min to appear)."
  value       = var.expose_argocd ? "kubectl -n argocd get ingress argocd-server -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'; echo" : null
}

output "grafana_port_forward" {
  description = "Open Grafana at http://localhost:3000 (user: admin)."
  value       = "kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80"
}

output "grafana_admin_user" {
  description = "Grafana admin user."
  value       = "admin"
}

output "grafana_admin_password" {
  description = "Grafana admin password. Show it with: terraform output -raw grafana_admin_password"
  value       = random_password.grafana_admin.result
  sensitive   = true
}

output "grafana_url_command" {
  description = "Prints the Grafana ALB hostname (only when expose_grafana = true)."
  value       = var.expose_grafana ? "kubectl -n observability get ingress kube-prometheus-stack-grafana -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'; echo" : null
}

output "prometheus_port_forward" {
  description = "Open Prometheus at http://localhost:9090."
  value       = "kubectl -n observability port-forward svc/kube-prometheus-stack-prometheus 9090:9090"
}

output "root_app_enabled" {
  description = "Whether the ArgoCD root application was created (false when gitops_repo_url is empty)."
  value       = var.gitops_repo_url != ""
}

output "db_secrets" {
  description = "Kubernetes Secrets (namespace claims) with SPRING_DATASOURCE_* for each service."
  value       = [for s in kubernetes_secret_v1.service_db : s.metadata[0].name]
}
