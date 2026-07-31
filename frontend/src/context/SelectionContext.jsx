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
  const [personaId, setPersonaIdState] = useState(null);

  // Kullanıcı değiştiğinde kendi kayıtlı seçimini yükle.
  useEffect(() => {
    const data = loadForUser(user?.email);
    setProviders(data.providers || []);
    setModeState(data.mode || CHAT_MODES.INDEPENDENT);
    setPersonaIdState(data.personaId || null);
  }, [user?.email]);

  const persist = useCallback(
    (nextProviders, nextMode, nextPersonaId) => {
      localStorage.setItem(
        storageKey(user?.email),
        JSON.stringify({ providers: nextProviders, mode: nextMode, personaId: nextPersonaId })
      );
    },
    [user?.email]
  );

  const toggleProvider = useCallback(
    (id) => {
      setProviders((prev) => {
        const isSelected = prev.includes(id);
        const next = isSelected ? prev.filter((p) => p !== id) : [...prev, id];
        persist(next, mode, personaId);
        return next;
      });
    },
    [mode, personaId, persist]
  );

  const setMode = useCallback(
    (nextMode) => {
      setModeState(nextMode);
      persist(providers, nextMode, personaId);
    },
    [providers, personaId, persist]
  );

  const setPersonaId = useCallback(
    (nextPersonaId) => {
      setPersonaIdState(nextPersonaId);
      persist(providers, mode, nextPersonaId);
    },
    [providers, mode, persist]
  );

  const setSelection = useCallback(
    (nextProviders, nextMode, nextPersonaId) => {
      const safeProviders = nextProviders || [];
      const safeMode = nextMode || CHAT_MODES.INDEPENDENT;
      setProviders(safeProviders);
      setModeState(safeMode);
      setPersonaIdState(nextPersonaId || null);
      persist(safeProviders, safeMode, nextPersonaId || null);
    },
    [persist]
  );

  const isValidSelection = providers.length >= MIN_SELECTION;

  return (
    <SelectionContext.Provider
      value={{
        providers,
        mode,
        personaId,
        toggleProvider,
        setMode,
        setPersonaId,
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
