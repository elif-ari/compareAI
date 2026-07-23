import { createContext, useContext, useState, useCallback } from "react";

// NOT: Backend'de bir auth servisi olmadığı için (istenen kapsam dışı),
// bu context tarayıcı localStorage üzerinde çalışan basit bir demo
// kayıt/giriş sistemidir. Amaç: kullanıcı akışını (kayıt -> giriş ->
// AI seçimi -> API key -> karşılaştırma) uçtan uca göstermek.
// Gerçek bir üretim ortamında bu kısım backend'deki bir /auth servisine bağlanmalıdır.

const USERS_KEY = "compareai_users";
const SESSION_KEY = "compareai_session";

const AuthContext = createContext(null);

// Basit, tersine çevrilemeyen olmayan bir "hash" - sadece localStorage'da
// düz metin parola tutmamak için. Gerçek şifreleme değildir.
function simpleHash(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i);
    hash |= 0;
  }
  return String(hash);
}

function loadUsers() {
  try {
    return JSON.parse(localStorage.getItem(USERS_KEY)) || {};
  } catch {
    return {};
  }
}

function saveUsers(users) {
  localStorage.setItem(USERS_KEY, JSON.stringify(users));
}

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(() => {
    try {
      return JSON.parse(localStorage.getItem(SESSION_KEY));
    } catch {
      return null;
    }
  });

  const register = useCallback((name, email, password) => {
    const normalizedEmail = email.trim().toLowerCase();
    if (!name.trim() || !normalizedEmail || !password) {
      throw new Error("Lütfen tüm alanları doldurun.");
    }
    if (password.length < 6) {
      throw new Error("Parola en az 6 karakter olmalı.");
    }
    const users = loadUsers();
    if (users[normalizedEmail]) {
      throw new Error("Bu e-posta ile zaten bir hesap var. Giriş yapmayı deneyin.");
    }
    users[normalizedEmail] = { name: name.trim(), email: normalizedEmail, passwordHash: simpleHash(password) };
    saveUsers(users);

    const session = { name: users[normalizedEmail].name, email: normalizedEmail };
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    setUser(session);
    return session;
  }, []);

  const login = useCallback((email, password) => {
    const normalizedEmail = email.trim().toLowerCase();
    const users = loadUsers();
    const record = users[normalizedEmail];
    if (!record || record.passwordHash !== simpleHash(password)) {
      throw new Error("E-posta veya parola hatalı.");
    }
    const session = { name: record.name, email: normalizedEmail };
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    setUser(session);
    return session;
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
