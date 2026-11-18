package com.claims.intake.storage;

import com.claims.intake.config.IntakeProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URL;
import java.time.Duration;

/**
 * Pre-signed URLs for the documents bucket. The browser uploads straight to S3, so file bytes never pass
 * through this service. Signing is local (no AWS call); the URL works only with the IRSA role's
 * permissions (PutObject/GetObject on claims/*).
 */
@Service
public class PresignedUrlService {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration uploadTtl;
    private final Duration previewTtl;

    public PresignedUrlService(S3Presigner presigner, IntakeProperties props) {
        this.presigner = presigner;
        this.bucket = props.documents().bucket();
        this.uploadTtl = props.documents().uploadUrlTtl();
        this.previewTtl = props.documents().previewUrlTtl();
    }

    /** A PUT URL for {@code key}. The upload must send this exact Content-Type (it is part of the signature). */
    public URL presignPut(String key, String contentType) {
        requireBucket();
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(uploadTtl).putObjectRequest(put).build()).url();
    }

    /** A short-lived GET URL, for adjusters and owners to preview a document. */
    public URL presignGet(String key) {
        requireBucket();
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(previewTtl).getObjectRequest(get).build()).url();
    }

    public Duration uploadTtl() {
        return uploadTtl;
    }

    public boolean isConfigured() {
        return bucket != null && !bucket.isBlank();
    }

    private void requireBucket() {
        if (!isConfigured()) {
            throw new IllegalStateException("DOCUMENTS_BUCKET is not set");
        }
    }
}
