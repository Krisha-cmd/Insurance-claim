// Typed-ish wrappers over ENDPOINTS so pages never assemble paths themselves.
import { ENDPOINTS } from '../config/api.js';
import { api } from './client.js';

export const authApi = {
  login: (username, password) => api.post(ENDPOINTS.auth.login, { username, password }),
  logout: () => api.post(ENDPOINTS.auth.logout),
  me: () => api.get(ENDPOINTS.auth.me),
};

export const policyApi = {
  mine: () => api.get(ENDPOINTS.policy.mine),
  byNumber: (no) => api.get(ENDPOINTS.policy.byNumber(no)),
  coverage: (no) => api.get(ENDPOINTS.policy.coverage(no)),
};

export const claimsApi = {
  create: (body) => api.post(ENDPOINTS.claims.create, body),
  mine: () => api.get(ENDPOINTS.claims.mine),
  byId: (id) => api.get(ENDPOINTS.claims.byId(id)),
  status: (id) => api.get(ENDPOINTS.claims.status(id)),
  uploadUrl: (id, body) => api.post(ENDPOINTS.claims.uploadUrl(id), body),
  documents: (id) => api.get(ENDPOINTS.claims.documents(id)),
  queue: (params) => api.get(ENDPOINTS.claims.queue, { params }),
  all: (params) => api.get(ENDPOINTS.claims.all, { params }),
};

export const eligibilityApi = {
  get: (id) => api.get(ENDPOINTS.eligibility.get(id)),
  override: (id, body) => api.put(ENDPOINTS.eligibility.override(id), body),
};

export const assessmentApi = {
  get: (id) => api.get(ENDPOINTS.assessment.get(id)),
  adjust: (id, body) => api.put(ENDPOINTS.assessment.adjust(id), body),
};

export const approvalApi = {
  decide: (id, body) => api.post(ENDPOINTS.approval.decide(id), body),
  settlement: (id) => api.get(ENDPOINTS.approval.settlement(id)),
  retry: (id) => api.post(ENDPOINTS.approval.retry(id)),
};

export const auditApi = {
  trail: (id) => api.get(ENDPOINTS.audit.trail(id)),
  search: (params) => api.get(ENDPOINTS.audit.search, { params }),
};

export const analyticsApi = {
  summary: () => api.get(ENDPOINTS.analytics.summary),
  series: (metric, params = {}) => api.get(ENDPOINTS.analytics.series, { params: { metric, window: '1m', ...params } }),
  fraudFlags: () => api.get(ENDPOINTS.analytics.fraudFlags),
};

export const opsApi = {
  chaos: () => api.get(ENDPOINTS.ops.chaos),
  setChaos: (target, mode) => api.post(ENDPOINTS.ops.chaos, { target, mode }),
};
