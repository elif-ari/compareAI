import { createContext, useContext, useState, useCallback } from "react";
import axios from "axios";

// Kayıt/giriş artık backend'e (MySQL'deki app_users tablosuna) bağlı.
// Oturum bilgisi (aktif kullanıcı) yalnızca "hangi kullanıcı olarak
// tarayıcıdasın" bilgisini hatırlamak için localStorage'da tutulur;
// parola hiçbir zaman burada saklanmaz, backend'de BCrypt ile hash'lenir.

const API_BASE = "http://localhost:8080/api/auth";
const SESSION_KEY = "compareai_session";

const AuthContext = createContext(null);

function extractErrorMessage(error, fallback) {
  return error?.response?.data?.message || fallback;
}

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(() => {
    try {
      return JSON.parse(localStorage.getItem(SESSION_KEY));
    } catch {
      return null;
    }
  });

  const register = useCallback(async (name, email, password) => {
    try {
      const res = await axios.post(`${API_BASE}/register`, { name, email, password });
      localStorage.setItem(SESSION_KEY, JSON.stringify(res.data));
      setUser(res.data);
      return res.data;
    } catch (error) {
      throw new Error(extractErrorMessage(error, "Kayıt olurken bir hata oluştu."), { cause: error });
    }
  }, []);

  const login = useCallback(async (email, password) => {
    try {
      const res = await axios.post(`${API_BASE}/login`, { email, password });
      localStorage.setItem(SESSION_KEY, JSON.stringify(res.data));
      setUser(res.data);
      return res.data;
    } catch (error) {
      throw new Error(extractErrorMessage(error, "Giriş yapılırken bir hata oluştu."), { cause: error });
    }
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(SESSION_KEY);
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, register, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth, AuthProvider içinde kullanılmalı");
  return ctx;
};
