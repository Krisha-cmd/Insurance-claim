# Same bucket/region/lock table as the infra stack, different key:
#
#   terraform init \
#     -backend-config="bucket=claims-dev-tfstate-<account_id>" \
#     -backend-config="region=ap-south-1" \
#     -backend-config="dynamodb_table=claims-dev-tf-locks"
terraform {
  backend "s3" {
    key     = "platform/terraform.tfstate"
    encrypt = true
  }
}
