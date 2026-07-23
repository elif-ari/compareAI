import { useNavigate } from "react-router-dom";
import { Check, ArrowRight, LogOut, BadgeCheck } from "lucide-react";
import { AI_GROUPS, MIN_SELECTION, MAX_SELECTION } from "../data/aiCatalog";
import { useSelection } from "../context/SelectionContext";
import { useAuth } from "../context/AuthContext";

const SelectProviders = () => {
  const { selectedIds, toggleProvider, isValidSelection } = useSelection();
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const count = selectedIds.length;
  const atMax = count >= MAX_SELECTION;

  const handleContinue = () => {
    if (!isValidSelection) return;
    navigate("/keys");
  };

  return (
    <div className="setup-page">
      <header className="setup-header">
        <div>
          <h1>Karşılaştırmak istediğin yapay zekaları seç</h1>
          <p>
            En az <strong>{MIN_SELECTION}</strong>, en fazla <strong>{MAX_SELECTION}</strong> yapay
            zeka seçebilirsin. Seçtiklerine bir sonraki adımda API anahtarını yapıştıracaksın.
            <br />
            <span className="setup-hint">
              Şu an için yalnızca <strong>ChatGPT (OpenAI)</strong>, <strong>Claude (Anthropic)</strong> ve{" "}
              <strong>Gemini (Google)</strong> gerçek zamanlı çalışıyor — diğerleri v1'de yalnızca
              arayüzü göstermek amacıyla seçilebilir.
            </span>
          </p>
        </div>
        <div className="setup-header-right">
          <span className="setup-user">{user?.name}</span>
          <button className="icon-btn" onClick={logout} title="Çıkış yap">
            <LogOut size={16} />
          </button>
        </div>
      </header>

      <div className="setup-groups">
        {AI_GROUPS.map((group) => (
          <section key={group.id} className="setup-group">
            <h2>{group.title}</h2>
            <div className="setup-grid">
              {group.providers.map((provider) => {
                const selected = selectedIds.includes(provider.id);
                const disabled = !selected && atMax;
                return (
                  <button
                    type="button"
                    key={provider.id}
                    className={`provider-chip ${selected ? "selected" : ""} ${disabled ? "disabled" : ""}`}
                    onClick={() => !disabled && toggleProvider(provider.id)}
                    disabled={disabled}
                  >
                    <div className="provider-chip-top">
                      <span className="provider-vendor">{provider.vendor}</span>
                      {provider.functional && (
                        <span className="live-badge" title="v1'de gerçek zamanlı çalışır">
                          <BadgeCheck size={12} /> Canlı
                        </span>
                      )}
                    </div>
                    <strong>{provider.name}</strong>
                    <span className="provider-detail">{provider.detail}</span>
                    {selected && (
                      <span className="provider-check">
                        <Check size={14} />
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </section>
        ))}
      </div>

      <footer className="setup-footer">
        <div className={`selection-counter ${isValidSelection ? "ok" : ""}`}>
          {count} / {MAX_SELECTION} seçildi
          {count < MIN_SELECTION && ` · en az ${MIN_SELECTION} seçmelisin`}
        </div>
        <button className="setup-continue" disabled={!isValidSelection} onClick={handleContinue}>
          Devam Et <ArrowRight size={16} />
        </button>
      </footer>
    </div>
  );
};

export default SelectProviders;
