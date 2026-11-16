output "cluster_name" {
  description = "Name of the EKS cluster."
  value       = module.eks.cluster_name
}

output "cluster_endpoint" {
  description = "HTTPS endpoint of the Kubernetes API."
  value       = module.eks.cluster_endpoint
}

output "cluster_ca" {
  description = "Base64-encoded certificate authority data of the cluster."
  value       = module.eks.cluster_certificate_authority_data
}

output "oidc_provider_arn" {
  description = "ARN of the cluster's IAM OIDC provider (used in IRSA trust policies)."
  value       = module.eks.oidc_provider_arn
}

output "oidc_provider_url" {
  description = "OIDC issuer host/path without https:// (used as the condition key prefix in IRSA trust policies)."
  value       = module.eks.oidc_provider
}

output "node_security_group_id" {
  description = "Security group attached to worker nodes (and, with the VPC CNI, to pods)."
  value       = module.eks.node_security_group_id
}

output "cluster_security_group_id" {
  description = "Security group created by EKS for the control plane."
  value       = module.eks.cluster_security_group_id
}

output "node_role_arn" {
  description = "IAM role ARN of the default managed node group."
  value       = module.eks.eks_managed_node_groups["default"].iam_role_arn
}
