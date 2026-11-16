import { API_BASE_URL, USE_MOCKS, REQUEST_TIMEOUT_MS, ENDPOINTS } from '../config/api.js';
import { getToken, clearSession } from '../auth/session.js';

// Mocks are loaded on demand so they are split out of the bundle and never run when mocks are off.
let mocksPromise = null;
const loadMocks = () => (mocksPromise ||= import('./mocks/index.js'));

export class ApiError extends Error {
  constructor(status, message, details) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

function buildQuery(params) {
  if (!params) return '';
  const qs = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') qs.append(k, String(v));
  });
  const s = qs.toString();
  return s ? `?${s}` : '';
}

function onUnauthorized() {
  clearSession();
  if (!window.location.pathname.startsWith('/login')) {
    window.location.assign('/login?expired=1');
  }
}

function readableMessage(status, body) {
  if (body && typeof body === 'object' && (body.message || body.error)) return body.message || body.error;
  if (status === 0) return 'Cannot reach the server. Check your connection and try again.';
  if (status === 403) return 'You do not have permission to do that.';
  if (status === 404) return 'Not found.';
  if (status === 409) return 'This item was changed by someone else. Reload and try again.';
  if (status >= 500) return 'The server had a problem. Please try again shortly.';
  return `Request failed (${status}).`;
}

async function request(method, path, body, { params } = {}) {
  const token = getToken();

  if (USE_MOCKS) {
    try {
      const { handleMock } = await loadMocks();
      return await handleMock({ method, path, body, params, token });
    } catch (err) {
      if (err.status === 401 && path !== ENDPOINTS.auth.login) onUnauthorized();
      throw new ApiError(err.status ?? 500, err.message || readableMessage(err.status ?? 500));
    }
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;

  let res;
  try {
    res = await fetch(API_BASE_URL + path + buildQuery(params), {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
  } catch (err) {
    if (err.name === 'AbortError') throw new ApiError(0, 'The request timed out. Please try again.');
    throw new ApiError(0, readableMessage(0));
  } finally {
    clearTimeout(timer);
  }

  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = text; }
  }

  if (res.status === 401 && path !== ENDPOINTS.auth.login) {
    onUnauthorized();
  }
  if (!res.ok) throw new ApiError(res.status, readableMessage(res.status, data), data);
  return data;
}

export const api = {
  get: (path, opts) => request('GET', path, undefined, opts),
  post: (path, body, opts) => request('POST', path, body ?? {}, opts),
  put: (path, body, opts) => request('PUT', path, body ?? {}, opts),
};

/**
 * PUT a file straight to a pre-signed URL (no auth header).
 * Uses XMLHttpRequest because fetch cannot report upload progress.
 */
export function uploadToSignedUrl(uploadUrl, file, onProgress) {
  if (USE_MOCKS) return loadMocks().then((m) => m.mockUpload(uploadUrl, file, onProgress));
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', uploadUrl);
    xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 300
      ? resolve()
      : reject(new ApiError(xhr.status, `Upload failed (${xhr.status}).`)));
    xhr.onerror = () => reject(new ApiError(0, 'Upload failed. Check your connection.'));
    xhr.ontimeout = () => reject(new ApiError(0, 'Upload timed out.'));
    xhr.send(file);
  });
}
