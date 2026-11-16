# Backend blocks cannot use variables, so bucket/region/lock table are passed at init time:
#
#   terraform init \
#     -backend-config="bucket=claims-dev-tfstate-<account_id>" \
#     -backend-config="region=ap-south-1" \
#     -backend-config="dynamodb_table=claims-dev-tf-locks"
#
# (The bootstrap stack prints these exact arguments as `backend_config_args`.)
terraform {
  backend "s3" {
    key     = "infra/terraform.tfstate"
    encrypt = true
  }
}
