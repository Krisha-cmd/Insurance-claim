package com.claims.intake.api.dto;

import com.claims.intake.domain.DocType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** POST /api/claims/{id}/documents/upload-url → {fileName, contentType, docType, size?} */
public record UploadUrlRequest(
        @NotBlank(message = "fileName is required.") @Size(max = 255, message = "The file name is too long.") String fileName,
        @NotBlank(message = "contentType is required.") String contentType,
        @NotNull(message = "Unknown document type.") DocType docType,
        @PositiveOrZero Long size) {
}
