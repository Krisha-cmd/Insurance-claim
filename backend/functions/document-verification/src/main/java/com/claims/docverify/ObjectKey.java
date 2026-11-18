package com.claims.docverify;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One uploaded object, with what its key says: {@code claims/{claimId}/{docType}/{documentId}-{fileName}}
 * (the key Claim Intake signs). Parts that cannot be read are null; KeyFormatRule then rejects the document.
 */
public record ObjectKey(String bucket, String key, UUID claimId, String docType, UUID documentId, String fileName,
                        Long eventSize, String eTag) {

    private static final Pattern FORMAT = Pattern.compile(
            "^claims/([^/]+)/([^/]+)/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})-([^/]+)$");

    public static ObjectKey of(String bucket, String key, Long size, String eTag) {
        Matcher m = FORMAT.matcher(key);
        if (!m.matches()) {
            return new ObjectKey(bucket, key, null, null, null, null, size, eTag);
        }
        return new ObjectKey(bucket, key, uuid(m.group(1)), m.group(2), uuid(m.group(3)), m.group(4), size, eTag);
    }

    /** True when the key matched the pattern and both ids are UUIDs. */
    public boolean wellFormed() {
        return claimId != null && documentId != null && docType != null && fileName != null;
    }

    /** Lower-case extension of the file name, or "" if none. */
    public String extension() {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
