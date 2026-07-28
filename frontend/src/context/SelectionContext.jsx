import { createContext, useContext, useState, useCallback, useEffect } from "react";
import { useAuth } from "./AuthContext";
import { MIN_SELECTION, CHAT_MODES } from "../data/aiCatalog";

const SelectionContext = createContext(null);

function storageKey(email) {
  return `compareai_newchat_${email || "guest"}`;
}

function loadForUser(email) {
  try {
    const raw = JSON.parse(localStorage.getItem(storageKey(email)));
    return {
      providers: raw?.providers || [],
      mode: raw?.mode || CHAT_MODES.INDEPENDENT,
    };
  } catch {
    return { providers: [], mode: CHAT_MODES.INDEPENDENT };
  }
}

export const SelectionProvider = ({ children }) => {
  const { user } = useAuth();
  const [providers, setProviders] = useState([]);
  const [mode, setModeState] = useState(CHAT_MODES.INDEPENDENT);

  // Kullanıcı değiştiğinde (login/logout) kendi kayıtlı seçimini yükle.
  useEffect(() => {
    const data = loadForUser(user?.email);
    setProviders(data.providers);
    setModeState(data.mode);
  }, [user?.email]);

  const persist = useCallback(
    (nextProviders, nextMode) => {
      localStorage.setItem(
        storageKey(user?.email),
        JSON.stringify({ providers: nextProviders, mode: nextMode })
      );
    },
    [user?.email]
  );

  const toggleProvider = useCallback(
    (id) => {
      setProviders((prev) => {
        const isSelected = prev.includes(id);
        const next = isSelected ? prev.filter((p) => p !== id) : [...prev, id];
        persist(next, mode);
        return next;
      });
    },
    [mode, persist]
  );

  const setMode = useCallback(
    (nextMode) => {
      setModeState(nextMode);
      persist(providers, nextMode);
    },
    [providers, persist]
  );

  // Kaydedilmiş bir sohbet geçmişten açıldığında, o konuşmanın kendi providers/mode
  // bilgisiyle seçim durumunu tek seferde (toggle toggle yapmadan) günceller.
  const setSelection = useCallback(
    (nextProviders, nextMode) => {
      const safeProviders = nextProviders || [];
      const safeMode = nextMode || CHAT_MODES.INDEPENDENT;
      setProviders(safeProviders);
      setModeState(safeMode);
      persist(safeProviders, safeMode);
    },
    [persist]
  );

  const isValidSelection = providers.length >= MIN_SELECTION;

  return (
    <SelectionContext.Provider
      value={{
        providers,
        mode,
        toggleProvider,
        setMode,
        setSelection,
        isValidSelection,
      }}
    >
      {children}
    </SelectionContext.Provider>
  );
};

export const useSelection = () => {
  const ctx = useContext(SelectionContext);
  if (!ctx) throw new Error("useSelection, SelectionProvider içinde kullanılmalı");
  return ctx;
};
