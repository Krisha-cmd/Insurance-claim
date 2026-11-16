variable "name" {
  description = "Name prefix; the table is called <name>-analytics."
  type        = string
}

variable "point_in_time_recovery" {
  description = "Enable point-in-time recovery (off for dev to save cost)."
  type        = bool
  default     = false
}
