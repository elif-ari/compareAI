import { useNavigate } from "react-router-dom";
import { ArrowLeft, ArrowRight, KeyRound, BadgeCheck, Clock } from "lucide-react";
import { getProviderById } from "../data/aiCatalog";
import { useSelection } from "../context/SelectionContext";

const ApiKeys = () => {
  const navigate = useNavigate();
  const { selectedIds, apiKeys, setApiKey, keysComplete } = useSelection();

  const providers = selectedIds.map(getProviderById).filter(Boolean);

  const handleContinue = () => {
    if (!keysComplete) return;
    navigate("/compare");
  };

  const handleSkip = () => {
    // Sadece arayüzde hızlı dolaşmak için: key girilmemiş sağlayıcılara
    // geçici bir test değeri koyup doğrudan karşılaştırma ekranına geçer.
    // Gerçek bir API çağrısı yapılmıyor (Canlı olanlar zaten mock backend'i kullanıyor).
    providers.forEach((provider) => {
      if (!(apiKeys[provider.id] || "").trim()) {
        setApiKey(provider.id, "TEST-KEY");
      }
    });
    navigate("/compare");
  };

  return (
    <div className="setup-page">
      <header className="setup-header">
        <div>
          <h1>API anahtarlarını yapıştır</h1>
          <p>
            Seçtiğin her yapay zeka için kendi API anahtarını buraya yapıştır. Anahtarlar yalnızca
            tarayıcında saklanır.
            <br />
            <span className="setup-hint">
              <strong>Canlı</strong> etiketli olanlar (ChatGPT, Claude, Gemini) gerçekten backend
              üzerinden yanıt verir; diğerleri v1'de yalnızca alanın nasıl çalışacağını göstermek
              içindir.
            </span>
          </p>
        </div>
      </header>

      <div className="keys-list">
        {providers.map((provider) => (
          <div key={provider.id} className="key-row">
            <div className="key-row-info">
              <div className="key-row-title">
                <strong>{provider.name}</strong>
                {provider.functional ? (
                  <span className="live-badge">
                    <BadgeCheck size={12} /> Canlı
                  </span>
                ) : (
                  <span className="soon-badge">
                    <Clock size={12} /> v2'de aktif
                  </span>
                )}
              </div>
              <span className="key-row-vendor">{provider.vendor} · {provider.detail}</span>
            </div>
            <label className="key-input">
              <KeyRound size={15} />
              <input
                type="password"
                placeholder={`${provider.vendor} API anahtarını yapıştır`}
                value={apiKeys[provider.id] || ""}
                onChange={(e) => setApiKey(provider.id, e.target.value)}
                autoComplete="off"
              />
            </label>
          </div>
        ))}
      </div>

      <footer className="setup-footer">
        <button className="setup-back" onClick={() => navigate("/select")}>
          <ArrowLeft size={16} /> Seçimi düzenle
        </button>
        <button className="setup-skip" onClick={handleSkip}>
          Atla (arayüzü test et)
        </button>
        <button className="setup-continue" disabled={!keysComplete} onClick={handleContinue}>
          Karşılaştırmaya Başla <ArrowRight size={16} />
        </button>
      </footer>
    </div>
  );
};

export default ApiKeys;
