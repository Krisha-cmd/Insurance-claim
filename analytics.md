# Analytics: aggregations and per-minute windows

How the Flink audit job turns the stream of claim events into the numbers on the adjuster's **Analytics** page. It covers event time and watermarks, how a 1-minute window is aggregated and when it closes, the fraud and settlement processing, and where each result goes.

For the infrastructure around the job (MSK, EMR, submitting it), see [flink.md](flink.md). The code is in [backend/analytics/audit-flink](backend/analytics/audit-flink).

---

## 1. Every kind of analytics processing

The job reads one Kafka topic, `claim-events`, and runs four branches over it:

| # | Processing | Flink technique | Key | Output | Shown as |
|---|---|---|---|---|---|
| 1 | **Audit trail**: every event that belongs to a claim | stateless filter | none | DynamoDB `CLAIM#<claimId>` / `EVENT#<time>#<eventId>` | claim timeline, **Audit log** search |
| 2 | **Claims overview**: counts, approval rate, amounts and 3PC outcomes per minute | **1-minute tumbling event-time window** with an incremental aggregate | all events (`windowAll`) | DynamoDB `METRIC#claims_overview#1m` / `WINDOW#<start>`, and Kafka **`claim-aggregates`** | Analytics: "Claims in the last minute" tile, KPI tiles, time-series charts |
| 3 | **Fraud detection**: more than 2 new claims on one policy within 10 minutes | **10-minute sliding window, every 1 minute**, distinct-count aggregate | `policyNo` | DynamoDB `FRAUD#<policyNo>` / `WINDOW#<end>`, and Kafka **`claim-analytics`** | Analytics: fraud flags list |
| 4 | **Settlement view**: live state of each three-phase commit | **keyed state** (`ValueState`, 1-day TTL), no window | `txId` | DynamoDB `TX#<txId>` / `STATE` | settlement status |

```
                          ┌─▶ filter(claimId != null) ───────────────────────────▶ DynamoDB  CLAIM#… / EVENT#…
                          │
Kafka claim-events ──▶ ───┼─▶ windowAll(tumbling 1 min) ─ aggregate ─┬───────────▶ DynamoDB  METRIC#claims_overview#1m
 event time = occurredAt  │                                          └───────────▶ Kafka     claim-aggregates
 watermark: 10 s bound,   │
 advances when idle       ├─▶ filter(CLAIM_CREATED) ─ keyBy(policyNo) ─ sliding 10 min / 1 min ─ count > 2 ─┬─▶ DynamoDB FRAUD#…
                          │                                                                                  └─▶ Kafka claim-analytics
                          └─▶ filter(TX_*) ─ keyBy(txId) ─ KeyedProcessFunction + ValueState ──────────────▶ DynamoDB TX#… / STATE
```

---

## 2. Time: event time and watermarks

### Event time, not arrival time

Every event carries `occurredAt`, set by the service when the event happened. Windows are defined on **that** time, not on when Flink happens to read the event:

```java
.withTimestampAssigner((event, recordTs) -> event.getOccurredAt())
```

So a claim created at 22:31:58 is always counted in the 22:31 minute, even if Kafka or Flink delays it by a few seconds, and even when the job reprocesses yesterday's events after a restart. Processing-time windows would put the same claim into whichever minute Flink happened to see it in, and a replay would give different numbers.

### Watermarks: "has this minute finished?"

With event time, Flink needs to know when it has seen everything for a minute. A **watermark** is Flink's statement "no more events at or before time *W* will arrive". A window `[22:31:00, 22:32:00)` **fires** when the watermark reaches `22:31:59.999`.

Events can arrive slightly out of order, because seven services publish independently over 3 Kafka partitions. The watermark therefore trails the newest event by a bound:

```
watermark = newest occurredAt seen − 10 s           (MAX_OUT_OF_ORDERNESS)
```

An event up to 10 s older than the newest one is still counted in its correct minute. The pipeline test covers this: a `REJECTED` that arrives 5 s out of order still lands in the right window.

### The quiet-stream problem, and the fix

A standard bounded-out-of-orderness watermark **only moves when a new event arrives**. That gives this failure on a low-traffic system:

```
22:31:58  last claim event                      watermark = 22:31:48
22:32:00  the 22:31 minute ends                 watermark = 22:31:48   (no new event, so it can't move)
22:36:00  still no events                       watermark = 22:31:48   → the 22:31 window never closes
```

The "Claims in the last minute" tile then never shows the claims just filed. They only appear once *some later event*, at 22:32:10 or after, pushes the watermark past 22:32. `withIdleness(1 min)` doesn't help: it stops an idle partition from *holding back* the watermark, but it never *moves* it.

[IdleAdvancingWatermarks](backend/analytics/audit-flink/src/main/java/com/claims/analytics/IdleAdvancingWatermarks.java) fixes this. It runs once per Kafka partition:

```
while events flow:                watermark = newest occurredAt − 10 s                        (as before)
after 5 s without an event:       watermark = max(newest occurredAt, wall clock) − 10 s
before the first event:           no watermark (the partition is ignored until it has data)
```

```
22:31:58  last claim event                      watermark = 22:31:48
22:32:03  5 s quiet → follows the clock         watermark = 22:31:53
22:32:10                                        watermark = 22:32:00  → the 22:31 window fires
```

A minute is therefore closed about **10–15 seconds after it ends**, whether or not more traffic arrives.

Why this is safe:

- **Live events are never late.** An event produced "now" arrives within milliseconds, well ahead of `now − 10 s`.
- **Replays aren't broken.** After a restart the partition is busy replaying, so it isn't quiet, and the watermark follows the replayed events. The clock is used only once a partition has caught up and gone quiet.
- **A partition that never connects can't jump ahead.** Before its first event it emits nothing, and after 1 minute `withIdleness` excludes it from the combined watermark.
- **It never goes backwards.** The generator only emits a watermark higher than its last one.

The only event that would now be "late" is one delayed by **more than 10 s** after a partition has already gone quiet. Such an event is still written to the audit trail, which isn't windowed, but it isn't added to an already-closed minute.

The combined watermark is the minimum across all partitions. A minute closes only when every active partition has passed it.

---

## 3. The 1-minute claims overview

### Tumbling windows

```java
events.windowAll(TumblingEventTimeWindows.of(Duration.ofMinutes(1)))
      .aggregate(new ClaimsOverviewAggregate(), new ClaimsOverviewWindowFunction())
```

A **tumbling** window is a fixed, non-overlapping bucket. Every event belongs to exactly one minute, aligned to the clock (`22:31:00–22:32:00`, `22:32:00–22:33:00`, …). `windowAll` means one window covers the whole stream, not one per key, because the overview is platform-wide.

### Incremental aggregation

[ClaimsOverviewAggregate](backend/analytics/audit-flink/src/main/java/com/claims/analytics/functions/ClaimsOverviewAggregate.java) is a Flink `AggregateFunction`. Flink doesn't keep the minute's events. It keeps one small **accumulator** per open window and folds each event into it as it arrives:

```java
class OverviewAcc {
    Map<String, Long> countsByStatus;   // events per toStatus
    long claimsCreated;
    BigDecimal totalClaimed;
    long settlementCommits;
    long settlementAborts;
}
```

| Step | Method | What happens |
|---|---|---|
| a window opens | `createAccumulator()` | an empty accumulator |
| each event | `add(event, acc)` | `toStatus` present → `countsByStatus[toStatus] += 1`. `CLAIM_CREATED` → `claimsCreated += 1`, `totalClaimed += amount`. `TX_COMMITTED` / `TX_ABORTED` → `settlementCommits` / `settlementAborts += 1` |
| the window fires | `getResult(acc)` | derives the ratios (below) |
| two partial accumulators meet | `merge(a, b)` | sums the counters and merges the status maps |

Derived when the window fires:

```
approvalRate = APPROVED / (APPROVED + REJECTED)       0 when no decisions were made in that minute
avgClaimed   = totalClaimed / claimsCreated           0 when no claims were created; 2 decimals, HALF_UP
```

Memory per window is constant, whether the minute had 3 events or 30,000. Then [ClaimsOverviewWindowFunction](backend/analytics/audit-flink/src/main/java/com/claims/analytics/functions/ClaimsOverviewWindowFunction.java) stamps the result with `windowStart` and `windowEnd`.

### What a window record looks like

```json
{"type":"WindowAggregate","windowStart":"2026-11-26T22:31:00Z","windowEnd":"2026-11-26T22:32:00Z",
 "countsByStatus":{"SUBMITTED":2,"DOCS_PENDING":2},"claimsCreated":2,"approvalRate":0.0,
 "totalClaimed":150000,"avgClaimed":75000.00,"settlementCommits":0,"settlementAborts":0}
```

- **Kafka `claim-aggregates`**: one record per closed minute, as above.
- **DynamoDB**: `PK = METRIC#claims_overview#1m`, `SK = WINDOW#<windowStart>`, with a `ttl` of 7 days. Because the key is the window start, reprocessing a minute **overwrites** the same item, so a replay never double-counts.

**A minute with no events produces no window and no item.** The ops-dashboard fills those gaps with zeros, so the charts have no holes.

### How the Analytics page reads it

The ops-dashboard ([AnalyticsService](backend/services/ops-dashboard/src/main/java/com/claims/ops/service/AnalyticsService.java)) queries DynamoDB:

| Tile or chart | Logic |
|---|---|
| **Claims in the last minute** | the **newest** window item (`Query … ScanIndexForward=false, Limit 1`), if it started within the 30-minute lookback. Its `claimsCreated`, with its time range shown underneath. So it shows the *most recent minute that had activity*. It only appears once that minute has closed, about 10–15 s after it ends |
| **Approval rate, average claimed, 3PC commits and aborts** | the sum over all windows in the last 30 minutes (`lookback-minutes`), then the ratio |
| **Time series** (`/api/analytics/series`) | one point per minute from the lookback start to the last closed minute; missing minutes become 0 |
| **Fraud flags** | GSI1 query `TYPE#FraudFlag` over the last 24 h, newest first, one per policy |

---

## 4. Fraud detection: sliding windows

```java
events.filter(e -> CLAIM_CREATED.equals(e.getEventType()) && e.getPolicyNo() != null)
      .keyBy(ClaimEvent::getPolicyNo)
      .window(SlidingEventTimeWindows.of(Duration.ofMinutes(10), Duration.ofMinutes(1)))
      .aggregate(new FraudCountAggregate(), new FraudFlagWindowFunction(2, 10))
```

- **Keyed by policy.** Each policy has its own windows, and Flink spreads the policies across parallel tasks.
- **Sliding windows.** Each window is 10 minutes long, and a new one starts every minute, so they overlap: `[22:22, 22:32)`, `[22:23, 22:33)`, …. Every claim belongs to 10 windows. This catches a burst wherever it falls. A tumbling 10-minute window would miss 3 claims at 22:28, 22:31 and 22:33, because a window boundary splits them.
- **A distinct count.** [FraudCountAggregate](backend/analytics/audit-flink/src/main/java/com/claims/analytics/functions/FraudCountAggregate.java) collects claim IDs in a sorted set, so a replayed `CLAIM_CREATED` counts once.
- **The threshold.** [FraudFlagWindowFunction](backend/analytics/audit-flink/src/main/java/com/claims/analytics/functions/FraudFlagWindowFunction.java) emits a `FraudFlag` only when the count is **strictly greater than 2**: 3 or more claims on one policy within 10 minutes.
- **One burst gives several flags**, one for each window that holds all of it, each with a different `windowEnd`. The ops-dashboard keeps only the newest flag per policy.

Example, from the pipeline test: claims on `P-FRAUD` at 14:02, 14:05 and 14:08 are flagged in the four windows ending 14:09, 14:10, 14:11 and 14:12. Claims on `P-SPREAD` at 14:03, 14:10:30 and 14:18 are never all inside one 10-minute window, so they are never flagged.

Output: DynamoDB `FRAUD#<policyNo>` / `WINDOW#<windowEnd>` (TTL 7 days, GSI1 `TYPE#FraudFlag`), and Kafka **`claim-analytics`**:

```json
{"type":"FraudFlag","policyNo":"POL-2026-00017","windowStart":"…","windowEnd":"…","windowMinutes":10,
 "claimCount":3,"claimIds":["c-1","c-2","c-3"]}
```

---

## 5. Settlement view: keyed state without windows

A three-phase commit is a short conversation of `TX_*` events: started, votes, phase changes, then committed or aborted. It has no natural time window, so it uses **keyed state** instead:

```java
events.filter(ClaimEvent::isTxEvent).keyBy(ClaimEvent::getTxId).process(new SettlementViewFunction())
```

[SettlementViewFunction](backend/analytics/audit-flink/src/main/java/com/claims/analytics/functions/SettlementViewFunction.java) keeps one `SettlementView` per transaction in a `ValueState`, and updates it on every event:

| Event | Effect on the view |
|---|---|
| `TX_STARTED` | its `service` becomes the coordinator |
| any event with `phase` | moves the current phase |
| any event with `participant` | sets that participant's `vote` and/or `state` |
| `TX_COMMITTED` / `TX_ABORTED` | sets the outcome |
| every event | `startedAt` = earliest time seen, `updatedAt` = latest (events may arrive out of order) |

After each event it emits the updated view, which is upserted to `TX#<txId>` / `STATE`. The state expires **1 day** after its last write, so finished transactions don't accumulate forever.

---

## 6. The audit trail

Not an aggregation. Every event that has a `claimId` is written as its own item:

```
PK = CLAIM#<claimId>      SK = EVENT#<occurredAt ISO, ms>#<eventId>
GSI1PK = TYPE#<eventType>  GSI1SK = <occurredAt>
```

The sort key starts with the timestamp, so one `Query` on `CLAIM#<id>` returns a claim's history in order. GSI1 serves the audit log search by event type and time range. The audit trail is part of the business requirement "every step is audited", so it has no TTL.

---

## 7. Correctness guarantees

| Concern | How it is handled |
|---|---|
| **Crash or restart** | **Checkpoints every 30 s**, exactly-once, to `s3://<artifacts>/flink/checkpoints`. They hold the open windows' accumulators, the settlement state and the Kafka offsets. On restart Flink restores them and carries on |
| **Replay** (from a checkpoint, or `--starting-offsets earliest`) | Every DynamoDB write is a PUT on a deterministic key (window start, policy + window end, txId, eventId), so the same result overwrites the same item. Fraud counts use distinct claim IDs |
| **Kafka outputs** | `AT_LEAST_ONCE`. A consumer of `claim-aggregates` or `claim-analytics` may see a window twice after a failure, and should upsert on `windowStart` (or `policyNo` + `windowEnd`) |
| **Out-of-order events** | up to 10 s, by the watermark bound |
| **Quiet periods** | the idle-advancing watermark closes each minute about 10–15 s after it ends |
| **Ordering per claim** | Kafka partitions by claim ID, so one claim's events arrive in order |
| **Bad input** | invalid JSON, or a missing `eventId` / `eventType` / `occurredAt`: logged and skipped, without stopping the job |

---

## 8. Kafka topics written by the job

| Topic | Content | One record per |
|---|---|---|
| **`claim-aggregates`** | the per-minute claims overview (`"type":"WindowAggregate"`) | closed 1-minute window that had events |
| **`claim-analytics`** | fraud flags (`"type":"FraudFlag"`) | policy × sliding window over the threshold |

They are separate so a consumer can subscribe to just the steady stream of metrics, or just the alerts. Both topics are created by the `kafka-topics` Job (`kafka_topics` in [platform/variables.tf](infra/terraform/platform/variables.tf)). Their names are job arguments: `--aggregates-topic` and `--results-topic`.

To read them from inside the cluster:

```bash
kubectl -n claims run kcat --rm -it --restart=Never --image=edenhill/kcat:1.7.1 -- \
  -b <msk_bootstrap_brokers> -t claim-aggregates -C -o -5 -e
```

---

## 9. Changing the analytics

| Change | Where |
|---|---|
| Window size, slide, out-of-order bound, idle advance, fraud threshold | the constants at the top of [AuditAnalyticsJob](backend/analytics/audit-flink/src/main/java/com/claims/analytics/AuditAnalyticsJob.java): `OVERVIEW_WINDOW`, `FRAUD_WINDOW`, `FRAUD_SLIDE`, `MAX_OUT_OF_ORDERNESS`, `CLOCK_ADVANCE_AFTER`, `FRAUD_THRESHOLD` |
| A new metric in the overview | a field in `OverviewAcc`, updated in `add` and `merge`, and output in `getResult` (`ClaimsOverview`), then written in `DynamoItems.overviewItem`. Read it in `AnalyticsService` |
| A new analysis | a new branch in `AuditAnalyticsJob.analytics(...)` with its own `uid`, a converter in `DynamoItems` with a new `PK` prefix, and optionally a new topic |
| Topic names | job arguments `--aggregates-topic`, `--results-topic`; add new topics to `kafka_topics` in `platform/variables.tf` |

Then build, test and redeploy:

```bash
cd backend && mvn -f analytics/audit-flink/pom.xml package      # unit tests + MiniCluster pipeline test
# upload target/audit-flink.jar to s3://<artifacts>/flink/ and resubmit (flink.md, "Submitting the job").
# Use --starting-offsets earliest to recompute history with the new logic. It is safe, because all writes are idempotent.
```

Keep every operator's `uid(...)` stable. Flink uses those IDs to match the state in a checkpoint or savepoint to operators after a change.
