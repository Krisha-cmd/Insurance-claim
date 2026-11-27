package com.claims.analytics;

import com.claims.analytics.model.ClaimEvent;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.eventtime.WatermarkOutput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bug this guards against: a claim filed at 22:31:58 with no later event never closed the 22:31 window,
 * because a bounded-out-of-orderness watermark only moves when an event arrives.
 */
class IdleAdvancingWatermarksTest {

    static final long T_2231_58 = Instant.parse("2026-11-26T22:31:58Z").toEpochMilli();
    static final long END_OF_2231_WINDOW = Instant.parse("2026-11-26T22:32:00Z").toEpochMilli();

    final AtomicLong now = new AtomicLong(T_2231_58 + 200); // the event reaches Flink 200 ms after it happened
    final IdleAdvancingWatermarks generator =
            new IdleAdvancingWatermarks(Duration.ofSeconds(10), Duration.ofSeconds(5), now::get);
    final Recorder out = new Recorder();

    @Test
    void emitsNothingBeforeTheFirstEvent() {
        now.addAndGet(60_000);
        generator.onPeriodicEmit(out);
        assertThat(out.watermarks).isEmpty();
    }

    @Test
    void followsTheEventsWhileTrafficFlows() {
        generator.onEvent(new ClaimEvent(), T_2231_58, out);
        now.addAndGet(1_000); // 1 s later: not idle yet
        generator.onPeriodicEmit(out);

        assertThat(out.last()).isEqualTo(T_2231_58 - 10_000 - 1);
    }

    @Test
    void closesTheLastWindowOnceTheInputGoesQuiet() {
        generator.onEvent(new ClaimEvent(), T_2231_58, out);

        // 22:32:11 wall clock: quiet for more than 5 s, and 10 s past the end of the 22:31 window
        now.set(END_OF_2231_WINDOW + 11_000);
        generator.onPeriodicEmit(out);

        // a window [22:31, 22:32) fires when the watermark reaches its last millisecond, 22:31:59.999
        assertThat(out.last()).isGreaterThanOrEqualTo(END_OF_2231_WINDOW - 1);
    }

    @Test
    void neverGoesBackwards() {
        generator.onEvent(new ClaimEvent(), T_2231_58, out);
        now.set(END_OF_2231_WINDOW + 60_000);
        generator.onPeriodicEmit(out);
        long advanced = out.last();

        // a late, older event arrives: the watermark must not move back
        generator.onEvent(new ClaimEvent(), T_2231_58 - 30_000, out);
        generator.onPeriodicEmit(out);

        assertThat(out.watermarks).allMatch(w -> w <= advanced);
        assertThat(out.last()).isEqualTo(advanced);
    }

    static final class Recorder implements WatermarkOutput {
        final List<Long> watermarks = new ArrayList<>();

        @Override
        public void emitWatermark(Watermark watermark) {
            watermarks.add(watermark.getTimestamp());
        }

        @Override
        public void markIdle() {
        }

        @Override
        public void markActive() {
        }

        long last() {
            return watermarks.get(watermarks.size() - 1);
        }
    }
}
