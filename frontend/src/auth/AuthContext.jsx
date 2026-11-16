import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { authApi } from '../api/services.js';
import { clearSession, getStoredUser, getToken, saveSession } from './session.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => (getToken() ? getStoredUser() : null));

  const login = useCallback(async (username, password) => {
    const { token, user: u } = await authApi.login(username, password);
    saveSession(token, u);
    setUser(u);
    return u;
  }, []);

  const logout = useCallback(async () => {
    try { await authApi.logout(); } catch { /* logging out locally is enough */ }
    clearSession();
    setUser(null);
  }, []);

  const value = useMemo(() => ({ user, role: user?.role ?? null, isAuthenticated: Boolean(user), login, logout }), [user, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
