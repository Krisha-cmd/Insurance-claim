variable "name" {
  description = "Name prefix; repositories are named <name>/<repository>."
  type        = string
}

variable "repositories" {
  description = "Repository names to create, one per container image."
  type        = list(string)
  default     = ["login", "claim-intake", "policy", "eligibility", "assessment", "approval", "payment", "ops-dashboard", "frontend"]
}

variable "keep_last_images" {
  description = "How many images to keep per repository; older ones are expired."
  type        = number
  default     = 10
}
