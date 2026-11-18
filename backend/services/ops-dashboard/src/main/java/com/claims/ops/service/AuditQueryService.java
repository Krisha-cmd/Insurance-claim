package com.claims.ops.service;

import com.claims.common.error.ApiException;
import com.claims.ops.api.CurrentUser;
import com.claims.ops.api.dto.AuditEventResponse;
import com.claims.ops.api.dto.AuditPage;
import com.claims.ops.client.ClaimOwnerClient;
import com.claims.ops.config.OpsProperties;
import com.claims.ops.repository.AnalyticsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Audit items: {@code CLAIM#<claimId> / EVENT#<isoTs>#<eventId>}, GSI1 {@code TYPE#<eventType> / <isoTs>}. */
@Service
public class AuditQueryService {

    static final int MAX_PAGE = 500;

    private final AnalyticsRepository repo;
    private final AuditEventMapper mapper;
    private final ClaimOwnerClient owners;
    private final int maxResults;

    public AuditQueryService(AnalyticsRepository repo, AuditEventMapper mapper, ClaimOwnerClient owners, OpsProperties props) {
        this.repo = repo;
        this.mapper = mapper;
        this.owners = owners;
        this.maxResults = props.maxAuditResults();
    }

    /** One claim's timeline, oldest first. Adjusters see any claim; a policyholder only the claims they filed. */
    public List<AuditEventResponse> trail(UUID claimId, CurrentUser user) {
        if (!user.isAdjuster()) {
            UUID owner = owners.submittedBy(claimId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claim " + claimId + " not found."));
            if (!owner.equals(user.id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This claim belongs to another policyholder.");
            }
        }
        return repo.queryByPk("CLAIM#" + claimId, false, maxResults).stream().map(mapper::map).toList();
    }

    /**
     * Events of {@code type} (a frontend name such as STATUS_CHANGED, or an emitted one) between {@code from} and
     * {@code to}, newest first, optionally filtered by {@code q} (claim id or actor name). Without a type every
     * known type is queried.
     */
    public AuditPage search(String type, Instant from, Instant to, String q, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "page must be 0 or more and size between 1 and " + MAX_PAGE);
        }
        List<String> types = type == null || type.isBlank()
                ? List.copyOf(AuditEventMapper.KNOWN_TYPES) : mapper.emittedTypes(type.trim());
        String lo = from == null ? null : isoMillis(from);
        String hi = to == null ? null : isoMillis(to);

        List<AuditEventResponse> all = new ArrayList<>();
        for (String t : types) {
            repo.queryGsi1("TYPE#" + t, lo, hi, true, maxResults).forEach(i -> all.add(mapper.map(i)));
        }
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<AuditEventResponse> matching = all.stream()
                .filter(e -> needle.isEmpty()
                        || (e.claimId() != null && e.claimId().toLowerCase(Locale.ROOT).contains(needle))
                        || (e.actor().name() != null && e.actor().name().toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparing(AuditEventResponse::at).reversed())
                .limit(maxResults)
                .toList();
        int fromIndex = Math.min(page * size, matching.size());
        int toIndex = Math.min(fromIndex + size, matching.size());
        return new AuditPage(matching.subList(fromIndex, toIndex), page, size, matching.size());
    }

    private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * GSI1SK is always written with milliseconds (2026-11-16T14:05:12.331Z). Bounds must use the same form:
     * "…:12Z" would sort after "…:12.331Z" ('.' &lt; 'Z') and silently drop that event.
     */
    static String isoMillis(Instant t) {
        return MILLIS.format(t);
    }
}
