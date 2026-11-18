package com.claims.ops.api;

import com.claims.ops.api.dto.FraudFlagResponse;
import com.claims.ops.api.dto.SeriesResponse;
import com.claims.ops.api.dto.SummaryResponse;
import com.claims.ops.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** ENDPOINTS.analytics. ADJUSTER only. */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    public SummaryResponse summary() {
        return analytics.summary();
    }

    /** ?metric=claims_by_status|aborts&window=1m&from=<ISO instant> */
    @GetMapping("/series")
    public SeriesResponse series(@RequestParam(required = false) String metric,
                                 @RequestParam(defaultValue = "1m") String window,
                                 @RequestParam(required = false) Instant from) {
        return analytics.series(metric, window, from);
    }

    @GetMapping("/fraud-flags")
    public List<FraudFlagResponse> fraudFlags() {
        return analytics.fraudFlags();
    }
}
