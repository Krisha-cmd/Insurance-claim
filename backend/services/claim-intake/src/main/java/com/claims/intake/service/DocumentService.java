package com.claims.intake.service;

import com.claims.common.error.ApiException;
import com.claims.intake.api.CurrentUser;
import com.claims.intake.api.dto.DocumentResponse;
import com.claims.intake.api.dto.UploadUrlRequest;
import com.claims.intake.api.dto.UploadUrlResponse;
import com.claims.intake.config.IntakeProperties;
import com.claims.intake.domain.Actor;
import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimDocument;
import com.claims.intake.domain.ClaimStateMachine;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.domain.DocType;
import com.claims.intake.domain.VerificationStatus;
import com.claims.intake.mapper.ClaimMapper;
import com.claims.intake.messaging.VerificationResultMessage;
import com.claims.intake.repository.ClaimDocumentRepository;
import com.claims.intake.repository.ClaimRepository;
import com.claims.intake.storage.PresignedUrlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final Set<ClaimStatus> ACCEPTS_UPLOADS = EnumSet.of(ClaimStatus.SUBMITTED, ClaimStatus.DOCS_PENDING);
    public static final Actor VERIFIER = Actor.system("document-verification");

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    /** No document with this id (the message cannot ever succeed). */
    public static class UnknownDocumentException extends RuntimeException {
        public UnknownDocumentException(String id) {
            super("Unknown document " + id);
        }
    }

    private final ClaimService claimService;
    private final ClaimRepository claims;
    private final ClaimDocumentRepository documents;
    private final ClaimStateMachine stateMachine;
    private final ClaimWorkflowOrchestrator orchestrator;
    private final PresignedUrlService urls;
    private final IntakeEvents events;
    private final ClaimMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final IntakeProperties.Documents config;

    public DocumentService(ClaimService claimService, ClaimRepository claims, ClaimDocumentRepository documents,
                           ClaimStateMachine stateMachine, ClaimWorkflowOrchestrator orchestrator, PresignedUrlService urls,
                           IntakeEvents events, ClaimMapper mapper, TransactionTemplate tx, Clock clock, IntakeProperties props) {
        this.claimService = claimService;
        this.claims = claims;
        this.documents = documents;
        this.stateMachine = stateMachine;
        this.orchestrator = orchestrator;
        this.urls = urls;
        this.events = events;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
        this.config = props.documents();
    }

    /**
     * Records a PENDING document and returns a pre-signed PUT URL (10 minutes) for
     * {@code claims/{claimId}/{docType}/{documentId}-{safeFileName}}. A new upload of a type replaces the
     * earlier REJECTED one.
     */
    public UploadUrlResponse createUpload(UUID claimId, UploadUrlRequest req, CurrentUser user) {
        String contentType = normaliseContentType(req.contentType());
        if (!config.allowedContentTypes().contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only PDF, JPEG and PNG files can be uploaded.");
        }
        if (req.size() != null && req.size() > config.maxSizeBytes()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Files can be at most " + config.maxSizeMb() + " MB.");
        }
        UUID documentId = UUID.randomUUID();
        String key = "claims/" + claimId + "/" + req.docType() + "/" + documentId + "-" + safeFileName(req.fileName());

        tx.executeWithoutResult(status -> {
            Claim claim = claimService.getOwned(claimId, user);
            if (!ACCEPTS_UPLOADS.contains(claim.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "Documents can no longer be added to this claim (status "
                        + claim.getStatus() + ").");
            }
            for (ClaimDocument earlier : documents.findByClaimIdAndDocTypeAndSupersededByIsNull(claimId, req.docType())) {
                if (earlier.getVerificationStatus() == VerificationStatus.REJECTED) {
                    earlier.supersedeBy(documentId);
                    documents.save(earlier);
                }
            }
            documents.save(new ClaimDocument(documentId, claimId, req.docType(), req.fileName().trim(), contentType,
                    req.size(), key, clock.instant()));
        });

        Instant expires = clock.instant().plus(urls.uploadTtl());
        return new UploadUrlResponse(urls.presignPut(key, contentType).toString(), documentId, key, "PUT",
                Map.of("Content-Type", contentType), expires);
    }

    public List<DocumentResponse> list(UUID claimId, CurrentUser user) {
        claimService.get(claimId, user);
        return mapper.documents(documents.findByClaimIdOrderByUploadedAtAsc(claimId));
    }

    /**
     * Applies one verification result. Idempotent on the document id: a repeated message changes nothing.
     * When every required type is VERIFIED the claim moves to DOCS_VERIFIED, and after the commit the
     * eligibility + assessment checks start.
     */
    public Outcome applyResult(VerificationResultMessage msg) {
        UUID documentId = msg.resolveDocumentId();
        VerificationStatus result = msg.verificationStatus();
        return tx.execute(status -> {
            ClaimDocument doc = documents.findById(documentId)
                    .orElseThrow(() -> new UnknownDocumentException(documentId.toString()));
            Claim claim = claims.findById(doc.getClaimId()).orElseThrow();

            Outcome outcome;
            if (doc.getVerificationStatus() == VerificationStatus.PENDING) {
                doc.applyResult(result, msg.reason(), msg.checksum(), msg.checkedAtOr(clock.instant()));
                documents.save(doc);
                events.documentResult(doc, claim.getPolicyNo(), clock.instant());
                outcome = Outcome.APPLIED;
            } else if (doc.getVerificationStatus() == result) {
                outcome = Outcome.DUPLICATE;
            } else {
                log.warn("Document {} is already {}; ignoring a later {} result", documentId, doc.getVerificationStatus(), result);
                outcome = Outcome.IGNORED;
            }

            // Also on a duplicate: if an earlier attempt committed the document but not the claim, finish it now.
            if (claim.getStatus() == ClaimStatus.DOCS_PENDING && allRequiredVerified(claim.getId())) {
                stateMachine.transition(claim, ClaimStatus.DOCS_VERIFIED, VERIFIER, "All required documents verified");
                UUID claimId = claim.getId();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        orchestrator.onDocumentsVerified(claimId);
                    }
                });
            }
            return outcome;
        });
    }

    boolean allRequiredVerified(UUID claimId) {
        Set<DocType> verified = documents.findByClaimIdOrderByUploadedAtAsc(claimId).stream()
                .filter(ClaimDocument::isCurrent)
                .filter(d -> d.getVerificationStatus() == VerificationStatus.VERIFIED)
                .map(ClaimDocument::getDocType)
                .collect(Collectors.toSet());
        return verified.containsAll(config.requiredTypes());
    }

    /** "application/pdf; charset=binary" → "application/pdf". */
    static String normaliseContentType(String contentType) {
        String base = contentType.split(";", 2)[0];
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /** Keeps letters, digits, dot, dash and underscore; at most 100 characters. */
    static String safeFileName(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_").replaceAll("^[._]+", "");
        if (name.length() > 100) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, 100 - ext.length()) + ext;
        }
        return name.isEmpty() ? "file" : name;
    }
}
