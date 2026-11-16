output "upload_events_queue_url" {
  description = "URL of doc-upload-events."
  value       = aws_sqs_queue.upload.url
}

output "upload_events_queue_arn" {
  description = "ARN of doc-upload-events. Depends on the queue policy so S3 can validate the notification target."
  value       = aws_sqs_queue.upload.arn
  depends_on  = [aws_sqs_queue_policy.upload]
}

output "upload_events_dlq_url" {
  description = "URL of the doc-upload-events dead-letter queue."
  value       = aws_sqs_queue.upload_dlq.url
}

output "results_queue_url" {
  description = "URL of doc-verification-results."
  value       = aws_sqs_queue.results.url
}

output "results_queue_arn" {
  description = "ARN of doc-verification-results."
  value       = aws_sqs_queue.results.arn
}

output "results_dlq_url" {
  description = "URL of the doc-verification-results dead-letter queue."
  value       = aws_sqs_queue.results_dlq.url
}
