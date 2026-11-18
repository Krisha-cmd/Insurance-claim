package com.claims.ops.api.dto;

import java.util.List;

/** GET /api/analytics/series: {metric, window, points} with one point per minute (minutes with no data are 0). */
public record SeriesResponse(String metric, String window, List<SeriesPoint> points) {
}
