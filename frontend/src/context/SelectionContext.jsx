import { createContext, useContext, useState, useCallback, useEffect } from "react";
import { useAuth } from "./AuthContext";
import { MIN_SELECTION, MAX_SELECTION } from "../data/aiCatalog";

const SelectionContext = createContext(null);

function storageKey(email) {
  return `compareai_selection_${email || "guest"}`;
}

function loadForUser(email) {
  try {
    const raw = JSON.parse(localStorage.getItem(storageKey(email)));
    return {
      selectedIds: raw?.selectedIds || [],
      apiKeys: raw?.apiKeys || {},
    };
  } catch {
    return { selectedIds: [], apiKeys: {} };
  }
}

export const SelectionProvider = ({ children }) => {
  const { user } = useAuth();
  const [selectedIds, setSelectedIds] = useState([]);
  const [apiKeys, setApiKeys] = useState({});

  // Kullanıcı değiştiğinde (login/logout) kendi kayıtlı seçimini yükle.
  useEffect(() => {
    const data = loadForUser(user?.email);
    setSelectedIds(data.selectedIds);
    setApiKeys(data.apiKeys);
  }, [user?.email]);

  const persist = useCallback(
    (nextSelectedIds, nextApiKeys) => {
      localStorage.setItem(
        storageKey(user?.email),
        JSON.stringify({ selectedIds: nextSelectedIds, apiKeys: nextApiKeys })
      );
    },
    [user?.email]
  );

  const toggleProvider = useCallback(
    (id) => {
      setSelectedIds((prev) => {
        const isSelected = prev.includes(id);
        let next;
        if (isSelected) {
          next = prev.filter((p) => p !== id);
        } else {
          if (prev.length >= MAX_SELECTION) return prev; // en fazla 10
          next = [...prev, id];
        }
        persist(next, apiKeys);
        return next;
      });
    },
    [apiKeys, persist]
  );

  const setApiKey = useCallback(
    (id, key) => {
      setApiKeys((prev) => {
        const next = { ...prev, [id]: key };
        persist(selectedIds, next);
        return next;
      });
    },
    [selectedIds, persist]
  );

  const clearSelection = useCallback(() => {
    setSelectedIds([]);
    setApiKeys({});
    persist([], {});
  }, [persist]);

  const isValidSelection = selectedIds.length >= MIN_SELECTION && selectedIds.length <= MAX_SELECTION;
  const keysComplete = selectedIds.length > 0 && selectedIds.every((id) => (apiKeys[id] || "").trim().length > 0);

  return (
    <SelectionContext.Provider
      value={{
        selectedIds,
        apiKeys,
        toggleProvider,
        setApiKey,
        clearSelection,
        isValidSelection,
        keysComplete,
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
