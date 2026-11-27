package com.claims.analytics;

import com.claims.analytics.model.ClaimEvent;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.eventtime.WatermarkGenerator;
import org.apache.flink.api.common.eventtime.WatermarkOutput;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Bounded out-of-orderness watermarks that keep moving when the input goes quiet.
 *
 * <p>Flink's standard generator only advances the watermark when a new event arrives
 * ({@code maxTimestamp - maxOutOfOrderness}). On a low-traffic stream that means the last minute's window
 * never closes: a claim filed at 22:31 is only counted once some later event, at 22:32:10 or after, pushes the
 * watermark past 22:32. {@code withIdleness} does not help: it stops an idle partition from holding the
 * watermark back, but it never moves the watermark forward.
 *
 * <p>This generator behaves exactly like the standard one while events flow. Once a partition (Kafka split) has
 * received no event for {@code idleAfter}, it also lets the watermark follow the wall clock, as
 * {@code now - maxOutOfOrderness}. An event produced "now" still arrives ahead of that watermark, so only an
 * event delayed by more than {@code maxOutOfOrderness} would be late.
 *
 * <p>The clock is only used after the first event of the partition. Until then nothing is emitted, so a
 * partition that is still connecting, or replaying old events after a restart, never jumps ahead of its data.
 */
public final class IdleAdvancingWatermarks implements WatermarkGenerator<ClaimEvent> {

    private final long maxOutOfOrdernessMs;
    private final long idleAfterMs;
    private final LongSupplier clock;

    private long maxTimestamp = Long.MIN_VALUE;
    private long lastEventAt = Long.MIN_VALUE;
    private long lastEmitted = Long.MIN_VALUE;

    public IdleAdvancingWatermarks(Duration maxOutOfOrderness, Duration idleAfter, LongSupplier clock) {
        this.maxOutOfOrdernessMs = maxOutOfOrderness.toMillis();
        this.idleAfterMs = idleAfter.toMillis();
        this.clock = clock;
    }

    @Override
    public void onEvent(ClaimEvent event, long eventTimestamp, WatermarkOutput output) {
        maxTimestamp = Math.max(maxTimestamp, eventTimestamp);
        lastEventAt = clock.getAsLong();
    }

    @Override
    public void onPeriodicEmit(WatermarkOutput output) {
        if (lastEventAt == Long.MIN_VALUE) {
            return; // no event yet on this partition: emit nothing, let withIdleness exclude it
        }
        // -1 as in Flink's BoundedOutOfOrdernessWatermarks: a watermark t means "no more events at or before t".
        long watermark = maxTimestamp - maxOutOfOrdernessMs - 1;
        long now = clock.getAsLong();
        if (now - lastEventAt >= idleAfterMs) {
            watermark = Math.max(watermark, now - maxOutOfOrdernessMs - 1);
        }
        if (watermark > lastEmitted) { // watermarks must never go backwards
            lastEmitted = watermark;
            output.emitWatermark(new Watermark(watermark));
        }
    }
}
