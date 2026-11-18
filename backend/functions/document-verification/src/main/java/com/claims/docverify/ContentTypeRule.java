package com.claims.docverify;

import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The stored Content-Type is one of ALLOWED_TYPES, and the file name's extension (if any) belongs to an
 * allowed type. The second check catches e.g. {@code setup.exe} uploaded with a signed
 * {@code Content-Type: application/pdf}.
 */
public class ContentTypeRule implements ValidationRule {

    private static final Map<String, String> TYPE_OF_EXTENSION = Map.of(
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png");

    private final Set<String> allowed;

    public ContentTypeRule(Set<String> allowed) {
        this.allowed = allowed;
    }

    @Override
    public Optional<String> check(ObjectKey key, HeadObjectResponse head) {
        String type = normalise(head.contentType());
        if (type.isEmpty() || !allowed.contains(type)) {
            return Optional.of("File type " + (type.isEmpty() ? "(none)" : type) + " is not allowed; upload a PDF, JPEG or PNG");
        }
        String ext = key.extension();
        if (!ext.isEmpty()) {
            String typeOfExt = TYPE_OF_EXTENSION.get(ext);
            if (typeOfExt == null || !allowed.contains(typeOfExt)) {
                return Optional.of("Files ending in ." + ext + " are not allowed; upload a PDF, JPEG or PNG");
            }
        }
        return Optional.empty();
    }

    static String normalise(String contentType) {
        if (contentType == null) return "";
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
