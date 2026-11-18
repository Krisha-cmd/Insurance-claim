package com.claims.ops.api;

import com.claims.common.error.ApiException;
import com.claims.ops.api.dto.AuditEventResponse;
import com.claims.ops.api.dto.AuditPage;
import com.claims.ops.service.AuditQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** ENDPOINTS.audit. The trail: its claim's owner or an ADJUSTER; the search: ADJUSTER. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditQueryService audit;

    public AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/claims/{claimId}")
    public List<AuditEventResponse> trail(@PathVariable UUID claimId, CurrentUser user) {
        return audit.trail(claimId, user);
    }

    /**
     * ?type=&from=&to=&q=&page=&size=. {@code from}/{@code to} take a date (yyyy-MM-dd, {@code to} inclusive, as
     * the frontend's date pickers send) or an ISO instant.
     */
    @GetMapping("/events")
    public AuditPage search(@RequestParam(required = false) String type,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "200") int size) {
        return audit.search(type, bound(from, false), bound(to, true), q, page, size);
    }

    static Instant bound(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            try {
                LocalDate d = LocalDate.parse(value);
                return endOfDay ? d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1)
                        : d.atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Dates must be yyyy-MM-dd or an ISO-8601 instant.");
            }
        }
    }
}
