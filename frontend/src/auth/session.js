// Token + user persistence in sessionStorage. Kept separate from React so the
// API client can clear the session on a 401 without importing the context.
const TOKEN_KEY = 'claims.token';
const USER_KEY = 'claims.user';

export function getToken() {
  try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; }
}

export function getStoredUser() {
  try {
    const raw = sessionStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
}

export function saveSession(token, user) {
  try {
    sessionStorage.setItem(TOKEN_KEY, token);
    sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  } catch { /* storage unavailable: session lives only in memory */ }
}

export function clearSession() {
  try {
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(USER_KEY);
  } catch { /* ignore */ }
}

export function homeFor(role) {
  return role === 'ADJUSTER' ? '/ops' : '/portal';
}
