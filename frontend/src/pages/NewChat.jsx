import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Check, ArrowLeft, ArrowRight, Info, Sparkles, Settings } from "lucide-react";
import { REAL_PROVIDERS, CHAT_MODES } from "../data/aiCatalog";
import { useSelection } from "../context/SelectionContext";
import { fetchPersonas } from "../services/personaApi";
import AdminPersonaModal from "../components/AdminPersonaModal";

const NewChat = () => {
  const { providers, mode, personaId, toggleProvider, setMode, setPersonaId, isValidSelection } = useSelection();
  const navigate = useNavigate();

  const [personas, setPersonas] = useState([]);
  const [isAdminModalOpen, setIsAdminModalOpen] = useState(false);

  useEffect(() => {
    loadPersonas();
  }, []);

  const loadPersonas = async () => {
    try {
      const data = await fetchPersonas();
      setPersonas(data);
      // Eğer seçili persona yoksa varsayılanı seç
      if (!personaId && data.length > 0) {
        const defaultP = data.find((p) => p.isDefault) || data[0];
        setPersonaId(defaultP.id);
      }
    } catch (err) {
      console.error("Personalar alınamadı:", err);
    }
  };

  const handleStart = () => {
    if (!isValidSelection) return;
    navigate("/chat");
  };

  return (
    <div className="setup-page">
      <header className="setup-header">
        <div>
          <h1>Yeni Sohbet</h1>
          <p>Konuşmanın nasıl başlayacağını belirle: yapay zekaların rolünü (Persona), modelleri ve çalışma modunu seç.</p>
        </div>
        <div className="setup-header-right">
          <button className="header-edit-btn" onClick={() => setIsAdminModalOpen(true)} title="Persona & İstemleri Yönet">
            <Settings size={14} /> Persona Yönetimi (Admin)
          </button>
          <button className="header-edit-btn" onClick={() => navigate("/dashboard")}>
            <ArrowLeft size={14} /> Dashboard'a dön
          </button>
        </div>
      </header>

      <div className="setup-groups">
        {/* Persona / Rol Seçim Grubu */}
        <section className="setup-group">
          <div className="setup-group-title-row">
            <h2>AI Kişiliği & Uzmanlık Rolü (Persona)</h2>
            <button className="persona-manage-link" onClick={() => setIsAdminModalOpen(true)}>
              + Yeni Persona Ekle / Düzenle
            </button>
          </div>
          <p className="newchat-hint">Yapay zekaların yanıt verirken üstleneceği rolü ve uzmanlık üslubunu seçin.</p>
          
          <div className="persona-grid">
            {personas.map((p) => {
              const selected = personaId === p.id;
              return (
                <button
                  type="button"
                  key={p.id}
                  className={`persona-chip ${selected ? "selected" : ""}`}
                  onClick={() => setPersonaId(p.id)}
                >
                  <div className="persona-chip-header">
                    <strong>{p.name}</strong>
                    {selected && (
                      <span className="persona-check">
                        <Check size={13} />
                      </span>
                    )}
                  </div>
                  <div className="persona-chip-title">{p.title}</div>
                  <p className="persona-chip-desc">{p.description}</p>
                </button>
              );
            })}
          </div>
        </section>

        {/* Yapay Zeka Seçim Grubu */}
        <section className="setup-group">
          <h2>Yapay Zeka Seçimi</h2>
          <p className="newchat-hint">En az bir model seçmelisin.</p>
          <div className="setup-grid">
            {REAL_PROVIDERS.map((provider) => {
              const selected = providers.includes(provider.id);
              return (
                <button
                  type="button"
                  key={provider.id}
                  className={`provider-chip ${selected ? "selected" : ""}`}
                  onClick={() => toggleProvider(provider.id)}
                >
                  <div className="provider-chip-top">
                    <span className="provider-vendor">{provider.vendor}</span>
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

        {/* Sohbet Modu Grubu */}
        <section className="setup-group">
          <h2>Sohbet Modu</h2>
          <div className="mode-options">
            <label className={`mode-option ${mode === CHAT_MODES.INDEPENDENT ? "selected" : ""}`}>
              <input
                type="radio"
                name="chat-mode"
                checked={mode === CHAT_MODES.INDEPENDENT}
                onChange={() => setMode(CHAT_MODES.INDEPENDENT)}
              />
              <div>
                <strong>Independent Chat</strong>
                <p>Her yapay zeka yalnızca kendi konuşma geçmişini görür. Diğerlerinin cevaplarından haberdar olmazlar.</p>
              </div>
            </label>

            <label className={`mode-option ${mode === CHAT_MODES.COMPARE ? "selected" : ""}`}>
              <input
                type="radio"
                name="chat-mode"
                checked={mode === CHAT_MODES.COMPARE}
                onChange={() => setMode(CHAT_MODES.COMPARE)}
              />
              <div>
                <strong>Compare Chat</strong>
                <p>Her mesaj otomatik olarak tüm yapay zekalara gider; hangi cevabı tercih ettiğini işaretleyebilirsin.</p>
                <span className="mode-soon">
                  <Info size={12} /> Örn: Bir soru sorduğunda modeller aynı anda cevap verir ve ortak rapor oluşturabilirsin.
                </span>
              </div>
            </label>
          </div>
        </section>
      </div>

      <footer className="setup-footer">
        <div className={`selection-counter ${isValidSelection ? "ok" : ""}`}>
          {providers.length} model seçildi
          {!isValidSelection && " · en az 1 seçmelisin"}
        </div>
        <button className="setup-continue" style={{ marginLeft: "auto" }} disabled={!isValidSelection} onClick={handleStart}>
          Sohbeti Başlat <ArrowRight size={16} />
        </button>
      </footer>

      {/* Admin Persona Modal */}
      <AdminPersonaModal
        isOpen={isAdminModalOpen}
        onClose={() => setIsAdminModalOpen(false)}
        onPersonasUpdated={loadPersonas}
      />
    </div>
  );
};

export default NewChat;
