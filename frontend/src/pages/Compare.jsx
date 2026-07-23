import { useState, useCallback, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Send, Loader2, Plus, Settings2, LogOut, Clock, Radio, X } from "lucide-react";
import axios from "axios";
import { getProviderById, CARD_PALETTE } from "../data/aiCatalog";
import { useSelection } from "../context/SelectionContext";
import { useAuth } from "../context/AuthContext";

const API_BASE = "http://localhost:8080/api/chat";

const Compare = () => {
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const { selectedIds } = useSelection();

  const providers = useMemo(() => selectedIds.map(getProviderById).filter(Boolean), [selectedIds]);

  const [inputText, setInputText] = useState("");
  const [isLoading, setIsLoading] = useState(false);

  // Aktif konuşma durumu
  const [conversationId, setConversationId] = useState(null);
  const [messages, setMessages] = useState([]); // bu konuşmaya ait tüm mesajlar (backend'den düz liste)
  const [headId, setHeadId] = useState(null); // conversation.currentMessageId (HEAD)

  // activeBranchProvider set edildiyse: kullanıcı "X ile devam et" demiş demektir.
  // Bu durumda üstteki kutudan gönderilen bir sonraki mesaj SADECE o sağlayıcıya gider
  // (backend'e askAllProviders:false gönderilir). "Tümüne dön" ile bu mod kapatılır.
  const [activeBranchProvider, setActiveBranchProvider] = useState(null);

  const activeBranchDefinition = useMemo(
    () => providers.find((p) => p.backendProvider === activeBranchProvider),
    [providers, activeBranchProvider]
  );

  // Şu anki HEAD'e göre gösterilecek "tur"u (son kullanıcı mesajı + ona bağlı AI cevapları) hesapla.
  const currentTurn = useMemo(() => {
    if (!headId || messages.length === 0) return { userMessage: null, aiMessages: [] };

    const byId = new Map(messages.map((m) => [m.id, m]));
    let head = byId.get(headId);
    if (!head) return { userMessage: null, aiMessages: [] };

    let lastUserMessage = head;
    while (lastUserMessage && lastUserMessage.role !== "USER") {
      lastUserMessage = lastUserMessage.parentMessageId ? byId.get(lastUserMessage.parentMessageId) : null;
    }
    if (!lastUserMessage) return { userMessage: null, aiMessages: [] };

    const aiMessages = messages.filter(
      (m) => m.role === "ASSISTANT" && m.parentMessageId === lastUserMessage.id
    );

    return { userMessage: lastUserMessage, aiMessages };
  }, [headId, messages]);

  const getAiMessage = useCallback(
    (backendProvider) => currentTurn.aiMessages.find((m) => m.provider === backendProvider),
    [currentTurn]
  );

  // Bir sağlayıcının konuşma boyunca verdiği EN SON cevabı bul (sadece bu turdaki değil).
  // "X ile devam et" bu mesajı HEAD yapar, böylece o sağlayıcının kendi dalında kaldığı yerden devam edilir.
  const getLatestMessageForProvider = useCallback(
    (backendProvider) => {
      const own = messages.filter((m) => m.role === "ASSISTANT" && m.provider === backendProvider);
      if (own.length === 0) return null;
      return own.reduce((latest, m) => (m.id > latest.id ? m : latest), own[0]);
    },
    [messages]
  );

  const startNewChat = () => {
    setConversationId(null);
    setMessages([]);
    setHeadId(null);
    setActiveBranchProvider(null);
    setInputText("");
  };

  const handleSendMessage = async () => {
    if (!inputText.trim() || isLoading) return;

    const messageToSend = inputText;
    setInputText("");
    setIsLoading(true);

    try {
      // conversationId varsa gönderiyoruz ki backend AYNI konuşmaya devam etsin
      // (parentMessageId göndermiyoruz: backend, boş bırakılırsa otomatik olarak
      // konuşmanın HEAD'inden devam ediyor - bkz. ChatService.resolveParentMessage).
      //
      // activeBranchProvider set edildiyse (bir kart için "devam et" tıklanmışsa)
      // SADECE o sağlayıcıya sorulur (askAllProviders:false); backend HEAD'in bir
      // ASSISTANT mesajı olmasını bekleyip o mesajın sağlayıcısına yönlendirir.
      const payload = {
        prompt: messageToSend,
        askAllProviders: activeBranchProvider === null,
      };
      if (conversationId) payload.conversationId = conversationId;

      const response = await axios.post(API_BASE, payload);
      const { conversationId: newConvId, currentMessageId, userMessage, aiResponses } = response.data;

      setConversationId(newConvId);
      setMessages((prev) => [...prev, userMessage, ...aiResponses]);
      setHeadId(currentMessageId);
      // activeBranchProvider bilerek SIFIRLANMIYOR: tek-sağlayıcı modundaysak
      // bir sonraki mesaj da varsayılan olarak aynı sağlayıcıya gitmeye devam etsin.
    } catch (error) {
      console.error("Backend hatası:", error);
      alert("Backend'e ulaşılamadı veya bir hata oluştu. Konsolu kontrol et.");
    } finally {
      setIsLoading(false);
    }
  };

  const handleContinueWith = async (backendProvider) => {
    const latestMessage = getLatestMessageForProvider(backendProvider);
    if (!latestMessage || !conversationId) {
      alert("Bu sağlayıcıdan henüz bir cevap yok.");
      return;
    }

    try {
      const res = await axios.post(`${API_BASE}/conversations/${conversationId}/select`, {
        messageId: latestMessage.id,
      });
      setHeadId(res.data.currentMessageId);
      setActiveBranchProvider(backendProvider);
    } catch (error) {
      console.error("Dal seçimi başarısız:", error);
      alert("Bu daldan devam edilemedi. Konsolu kontrol et.");
    }
  };

  const handleReturnToBroadcast = () => {
    setActiveBranchProvider(null);
  };

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  return (
    <div className="compare-page">
      <header className="header">
        <div className="header-left">
          <span className="header-brand">CompareAI</span>
          <span className="header-divider">·</span>
          <span>
            <strong>{providers.length}</strong> model seçili
          </span>
          <button className="icon-btn" onClick={startNewChat} title="Yeni sohbet">
            <Plus size={16} />
          </button>
        </div>
        <div className="header-right">
          <span className="setup-user">{user?.name}</span>
          <button className="header-edit-btn" onClick={() => navigate("/select")}>
            <Settings2 size={14} /> Seçimi düzenle
          </button>
          <button className="icon-btn" onClick={logout} title="Çıkış yap">
            <LogOut size={16} />
          </button>
        </div>
      </header>

      <div className="main-content">
        <div className="input-section">
          {activeBranchDefinition && (
            <div className="branch-banner">
              <Radio size={14} />
              <span>
                Şu an yalnızca <strong>{activeBranchDefinition.name}</strong> ile konuşuyorsun.
                Diğerleri bu turdaki soruyu görmeyecek.
              </span>
              <button className="branch-banner-close" onClick={handleReturnToBroadcast}>
                <X size={13} /> Tümüne dön
              </button>
            </div>
          )}
          <div className="input-box">
            <textarea
              rows="1"
              placeholder={
                activeBranchDefinition
                  ? `${activeBranchDefinition.name} ile devam et... (Enter ile gönder)`
                  : "Tüm yapay zekalara aynı anda sor... (Enter ile gönder)"
              }
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={isLoading}
            />
            <button className="send-btn" onClick={handleSendMessage} disabled={isLoading}>
              {isLoading ? <Loader2 size={18} className="animate-spin" /> : <Send size={18} />}
            </button>
          </div>
        </div>

        {/* AI Kartları */}
        <div className="cards-container">
          {providers.map((provider, index) => {
            const palette = CARD_PALETTE[index % CARD_PALETTE.length];
            const isActiveBranch = activeBranchProvider === provider.backendProvider;
            const isMutedThisTurn = activeBranchProvider !== null && !isActiveBranch;

            if (provider.functional) {
              const aiMessage = getAiMessage(provider.backendProvider);
              const hasAnyHistory = !!getLatestMessageForProvider(provider.backendProvider);
              return (
                <div
                  key={provider.id}
                  className={`ai-card ${isActiveBranch ? "active-branch" : ""} ${isMutedThisTurn ? "muted-card" : ""}`}
                  style={{ "--card-color": palette.bg, "--card-border": palette.border, "--card-text": palette.text }}
                >
                  <div className="card-header">
                    <div className="avatar" style={{ background: palette.bg }}>
                      {provider.vendor[0]}
                    </div>
                    <div>
                      <strong>{provider.name}</strong>
                      <div style={{ fontSize: "0.75rem", color: "#94a3b8" }}>
                        {provider.vendor} · {provider.detail}
                      </div>
                    </div>
                    <span className="live-badge card-live-badge">Canlı</span>
                  </div>
                  <div className="card-body">
                    {!currentTurn.userMessage
                      ? "Mesajınızı bekliyor..."
                      : aiMessage
                      ? aiMessage.content
                      : isMutedThisTurn
                      ? `Bu turda soru sorulmadı (şu an yalnızca ${activeBranchDefinition?.name} ile konuşuluyor).`
                      : "Cevap alınamadı."}
                  </div>
                  <div className="card-footer">
                    <button
                      className="continue-btn"
                      style={{ color: palette.text, borderColor: palette.border }}
                      onClick={() => handleContinueWith(provider.backendProvider)}
                      disabled={!hasAnyHistory || isActiveBranch}
                    >
                      {isActiveBranch ? "Şu an bu sağlayıcıdasın" : `${provider.name} ile devam et →`}
                    </button>
                  </div>
                </div>
              );
            }

            // Fonksiyonel olmayan (v1'de görsel amaçlı) sağlayıcı kartı
            return (
              <div
                key={provider.id}
                className="ai-card placeholder-card"
                style={{ "--card-color": palette.bg, "--card-border": palette.border, "--card-text": palette.text }}
              >
                <div className="card-header">
                  <div className="avatar" style={{ background: palette.bg }}>
                    {provider.vendor[0]}
                  </div>
                  <div>
                    <strong>{provider.name}</strong>
                    <div style={{ fontSize: "0.75rem", color: "#94a3b8" }}>
                      {provider.vendor} · {provider.detail}
                    </div>
                  </div>
                  <span className="soon-badge card-live-badge">
                    <Clock size={12} /> v2
                  </span>
                </div>
                <div className="card-body placeholder-body">
                  {currentTurn.userMessage
                    ? `Sorunuz alındı: "${currentTurn.userMessage.content}". Bu model için gerçek API entegrasyonu v2'de eklenecek.`
                    : "Bu model v1'de yalnızca arayüzü göstermek için burada; gerçek yanıt üretmiyor."}
                </div>
                <div className="card-footer">
                  <button className="continue-btn" disabled>
                    v2'de kullanılabilir olacak
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default Compare;
