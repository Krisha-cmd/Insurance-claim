package com.claims.ops.api.dto;

import java.util.List;

/** {items, page, size, total}, the paging shape the frontend reads. */
public record AuditPage(List<AuditEventResponse> items, int page, int size, long total) {
}
