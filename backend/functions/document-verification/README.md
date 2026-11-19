# Document verification (AWS Lambda)

A small, event-driven function. When a claim document lands in S3 it checks the file and publishes a
VERIFIED or REJECTED result for Claim Intake. It has no database and no Spring: just the AWS SDK v2 (with
the URL-connection HTTP client) and Jackson. It runs on Java 21 with SnapStart.

```
browser ──PUT (pre-signed)──▶ S3 documents bucket (claims/ prefix)
                                   │ s3:ObjectCreated:*
                                   ▼
                       SQS doc-upload-events (+ DLQ)
                                   │ event source mapping, batch 5, ReportBatchItemFailures
                                   ▼
                    Lambda  Handler::handleRequest  ── HeadObject ──▶ S3
                                   │ VerificationResult JSON
                                   ▼
                    SQS doc-verification-results ──▶ Claim Intake (VerificationResultListener)
```

Terraform deploys it as `com.claims.docverify.Handler::handleRequest` from
`target/document-verification.jar`. See [`infra/terraform/modules/lambda`](../../../infra/terraform/modules/lambda).

## What it does

Each SQS message body is an S3 event notification. For every created object in it, the handler does this:

1. **Parses the key** `claims/{claimId}/{docType}/{documentId}-{fileName}`, which is the key Claim Intake
   signs. URL-encoded keys (`+`, `%XX`) are decoded.
2. **Calls `HeadObject`** to get the stored Content-Type, size and ETag.
3. **Runs the rules.** Every rule runs, and all failures are listed in `reason`:

   | Rule | Rejects when |
   | --- | --- |
   | `KeyFormatRule` | the key isn't in the layout above, either id isn't a UUID, or `docType` isn't DISCHARGE_SUMMARY, FINAL_BILL, PRESCRIPTION, LAB_REPORT or ID_PROOF |
   | `NonEmptyRule` | the size is 0 |
   | `SizeRule` | the size is above `MAX_SIZE_MB`. A pre-signed PUT can't limit the size, so this is where the limit is enforced |
   | `ContentTypeRule` | the Content-Type isn't in `ALLOWED_TYPES`, or the file name's extension belongs to no allowed type (this catches `setup.exe` uploaded with a signed `application/pdf`) |

4. **Sends the result** to `RESULTS_QUEUE_URL`:

   ```json
   {"claimId":"…","documentId":"…","docType":"FINAL_BILL","status":"VERIFIED","reason":null,
    "checkedAt":"2026-11-18T10:00:01Z","s3Key":"claims/…/FINAL_BILL/…-bill.pdf","checksum":"etag:9b2c…"}
   ```

   `s3Key` and `checksum` go beyond the spec's fields; Claim Intake stores them.

## Failures

| Situation | Outcome |
| --- | --- |
| A rule fails | A **REJECTED result** (not a failure); the policyholder sees the reason and can upload again |
| The object is gone (HeadObject 404) | REJECTED: "The uploaded file no longer exists" |
| An S3 or SQS error: throttling, 5xx, network, or 403 | The message goes into **`batchItemFailures`**, so only that message is retried. After 3 receives it goes to the upload queue's DLQ |
| The body isn't an S3 event, or it's the `s3:TestEvent` | Logged as DROPPED / ignored; never retried, because retrying can't fix it |
| A key outside `claims/…` (no document id), or another bucket | Logged as IGNORED; there's no document to report on |
| An ObjectRemoved event | Ignored |

**Why 403 is retried.** The Lambda role has `s3:GetObject` but not `s3:ListBucket`, so S3 answers a
missing object with 403 rather than 404. A 403 is therefore retried and ends up in the DLQ instead of
producing a result.

**Resending results.** If a message holds several objects and a later one fails, the retry sends the
earlier results again. Claim Intake applies results idempotently per document, so that's harmless.

## Logging

There is one JSON line per document on stdout, so CloudWatch Logs Insights can filter on it:

```json
{"status":"VERIFIED","messageId":"…","claimId":"…","documentId":"…","docType":"FINAL_BILL","key":"claims/…","reason":null,"durationMs":42}
```

`status` is one of VERIFIED, REJECTED, RETRY, DROPPED or IGNORED. The SDK's own logging is switched off
(`slf4j-nop`).

## Cold start (SnapStart)

- **Built once, at init:** the S3 and SQS clients, the parser, the rules and the Jackson mapper are all
  created in the constructor.
- **Primed at init:** the constructor runs one in-memory parse, validate and serialise (no network), so
  class loading and Jackson's serializer set-up land in the SnapStart snapshot.
- **No VPC:** the function stays out of the VPC to avoid ENI set-up.
- **Measured so far:** init takes about 2.3 s on a dev laptop without SnapStart.
- **Not measured:** the under-1 s target relies on SnapStart restoring the snapshot after init, and that
  has not been measured on AWS yet.

## Configuration (set by Terraform)

| Variable | Default | |
| --- | --- | --- |
| `RESULTS_QUEUE_URL` | required | doc-verification-results |
| `ALLOWED_TYPES` | `application/pdf,image/jpeg,image/png` | must match Claim Intake's `allowed-content-types` |
| `MAX_SIZE_MB` | `10` | must match Claim Intake's `max-size-mb` |
| `DOCUMENTS_BUCKET` | empty | when set, events for other buckets are ignored |
| `AWS_REGION` | set by Lambda | |

## Build and test

```bash
cd backend
mvn -pl functions/document-verification -am package   # → target/document-verification.jar (shaded, ~12 MB)
```

The tests use real S3 event JSON in `src/test/resources/events/` (a PUT, an .exe, an oversize scan, a
URL-encoded key, two objects, an unknown docType, a bad key, a removal, and the `s3:TestEvent`):

| Test | Covers |
| --- | --- |
| `S3EventParserTest` | fields from a real event, decoding, several records, the test event and removals, bad keys, non-S3 bodies |
| `KeyFormatRuleTest`, `NonEmptyRuleTest`, `SizeRuleTest`, `ContentTypeRuleTest` | each rule, including an .exe disguised as a PDF |
| `DocumentValidatorTest` | a VERIFIED result's fields; every failed rule is listed |
| `HandlerTest` | with mocked `S3Client` / `SqsClient`: a PDF passes; an .exe is rejected; an oversize file is rejected; **an S3 error lands in `batchItemFailures` while the other message still succeeds**; SQS and network errors retry; a deleted object; several objects per message; ignored and dropped bodies; another bucket; the JSON log line |
| `ConfigTest` | the Terraform environment variables and their defaults |

To deploy, build the jar, then run `terraform apply` in `infra/terraform/infra` with `enable_lambda = true`.
The module's precondition stops the apply if the jar is missing.
