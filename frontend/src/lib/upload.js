import { claimsApi } from '../api/services.js';
import { uploadToSignedUrl } from '../api/client.js';

/** Request a pre-signed URL for the claim, then PUT the file straight to it. */
export async function uploadClaimDocument(claimId, docType, file, onProgress) {
  const { uploadUrl, documentId } = await claimsApi.uploadUrl(claimId, {
    fileName: file.name,
    contentType: file.type || 'application/octet-stream',
    docType,
    size: file.size,
  });
  await uploadToSignedUrl(uploadUrl, file, onProgress);
  return documentId;
}

export function fileSize(bytes) {
  if (!bytes) return '';
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
