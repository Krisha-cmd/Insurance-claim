# ---------------------------------------------------------------------------
# EC2 vCPU quota guard
#
# EKS worker nodes and EMR nodes are ordinary EC2 instances, so they share the account's
# "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances" vCPU quota (var.ec2_vcpu_quota).
# MSK brokers, RDS, NAT gateways and Lambda are not EC2 instances in this account and do not count.
#
# The plan fails here, before anything is created, if the sizing in the tfvars needs more vCPUs than
# the quota. EKS is counted at node_max_size, its largest possible size.
# ---------------------------------------------------------------------------

data "aws_ec2_instance_type" "node" {
  for_each      = toset(var.node_instance_types)
  instance_type = each.value
}

data "aws_ec2_instance_type" "emr" {
  for_each      = var.enable_emr ? toset(compact([var.emr_primary_instance_type, var.emr_core_instance_count > 0 ? var.emr_core_instance_type : ""])) : toset([])
  instance_type = each.value
}

locals {
  vcpu_eks = var.node_max_size * max([for t in data.aws_ec2_instance_type.node : t.default_vcpus]...)
  vcpu_emr = var.enable_emr ? (
    data.aws_ec2_instance_type.emr[var.emr_primary_instance_type].default_vcpus +
    (var.emr_core_instance_count > 0 ? var.emr_core_instance_count * data.aws_ec2_instance_type.emr[var.emr_core_instance_type].default_vcpus : 0)
  ) : 0
  vcpu_total = local.vcpu_eks + local.vcpu_emr
}

resource "terraform_data" "ec2_vcpu_quota" {
  input = local.vcpu_total

  lifecycle {
    precondition {
      condition     = local.vcpu_total <= var.ec2_vcpu_quota
      error_message = "This sizing needs ${local.vcpu_total} vCPUs (EKS ${local.vcpu_eks} at node_max_size = ${var.node_max_size}, EMR ${local.vcpu_emr}) but the EC2 quota is ${var.ec2_vcpu_quota}. Use smaller or fewer EKS nodes, set emr_core_instance_count = 0, or set enable_emr = false."
    }
  }
}

output "ec2_vcpu_usage" {
  description = "vCPUs the EC2 instances need at full size, against the account quota."
  value       = "${local.vcpu_total} of ${var.ec2_vcpu_quota} (EKS ${local.vcpu_eks}, EMR ${local.vcpu_emr})"
}
