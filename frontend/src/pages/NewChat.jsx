import { useNavigate } from "react-router-dom";
import { Check, ArrowLeft, ArrowRight, Info } from "lucide-react";
import { REAL_PROVIDERS, CHAT_MODES } from "../data/aiCatalog";
import { useSelection } from "../context/SelectionContext";

const NewChat = () => {
  const { providers, mode, toggleProvider, setMode, isValidSelection } = useSelection();
  const navigate = useNavigate();

  const handleStart = () => {
    if (!isValidSelection) return;
    navigate("/chat");
  };

  return (
    <div className="setup-page">
      <header className="setup-header">
        <div>
          <h1>Yeni Sohbet</h1>
          <p>Konuşmanın nasıl başlayacağını belirle: hangi yapay zekaları kullanmak istiyorsun ve hangi modda çalışsınlar.</p>
        </div>
        <div className="setup-header-right">
          <button className="header-edit-btn" onClick={() => navigate("/dashboard")}>
            <ArrowLeft size={14} /> Dashboard'a dön
          </button>
        </div>
      </header>

      <div className="setup-groups">
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
                <p>Yapay zekalar ortak konuşma bağlamını paylaşır; biri diğerinin cevabını da okuyabilir.</p>
                <span className="mode-soon">
                  <Info size={12} /> Örn: Claude'a sorduğun soruyu, sonra "ChatGPT ile devam et" deyip
                  hiçbir şey açıklamadan "sen ne düşünüyorsun?" diye sorduğunda ChatGPT, Claude'un o
                  soruya verdiği cevabı da bağlamında görür.
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
    </div>
  );
};

export default NewChat;
