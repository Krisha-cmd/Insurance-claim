package com.claims.intake.api;

import com.claims.intake.api.dto.DocumentResponse;
import com.claims.intake.api.dto.UploadUrlRequest;
import com.claims.intake.api.dto.UploadUrlResponse;
import com.claims.intake.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ENDPOINTS.claims: uploadUrl, documents. */
@RestController
@RequestMapping("/api/claims/{claimId}/documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /** Owner: a pre-signed S3 PUT URL (10 minutes) for one document. */
    @PostMapping("/upload-url")
    public UploadUrlResponse uploadUrl(@PathVariable UUID claimId, @Valid @RequestBody UploadUrlRequest body, CurrentUser user) {
        return documents.createUpload(claimId, body, user);
    }

    /** Owner or ADJUSTER: current documents with their verification status. */
    @GetMapping
    public List<DocumentResponse> list(@PathVariable UUID claimId, CurrentUser user) {
        return documents.list(claimId, user);
    }
}
