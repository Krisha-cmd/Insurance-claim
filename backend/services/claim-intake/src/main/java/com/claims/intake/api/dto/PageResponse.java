package com.claims.intake.api.dto;

import java.util.List;

/** {items, page, size, total}, the paging shape the frontend reads. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {
}
