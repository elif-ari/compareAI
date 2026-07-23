import { useState, useEffect, useCallback, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Send, MessageSquare, Loader2, Plus, Settings2, LogOut, Clock } from "lucide-react";
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
  const [conversations, setConversations] = useState([]);

  // Aktif konuşma durumu
  const [conversationId, setConversationId] = useState(null);
  const [messages, setMessages] = useState([]); // bu konuşmaya ait tüm mesajlar (backend'den düz liste)
  const [headId, setHeadId] = useState(null); // conversation.currentMessageId (HEAD)
  const [activeBranchProvider, setActiveBranchProvider] = useState(null); // en son "X ile devam et" seçilen sağlayıcı

  const fetchConversations = async () => {
    try {
      const res = await axios.get(`${API_BASE}/conversations`);
      setConversations(res.data);
    } catch (error) {
      console.error("Geçmiş sohbetler çekilemedi:", error);
    }
  };

  useEffect(() => {
    fetchConversations();
  }, []);

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

  const startNewChat = () => {
    setConversationId(null);
    setMessages([]);
    setHeadId(null);
    setActiveBranchProvider(null);
    setInputText("");
  };

  const loadConversation = async (id) => {
    try {
      setIsLoading(true);
      const res = await axios.get(`${API_BASE}/conversations/${id}`);
      setConversationId(res.data.id);
      setMessages(res.data.messages);
      setHeadId(res.data.currentMessageId);

      const byId = new Map(res.data.messages.map((m) => [m.id, m]));
      const head = byId.get(res.data.currentMessageId);
      setActiveBranchProvider(head && head.role === "ASSISTANT" ? head.provider : null);
    } catch (error) {
      console.error("Konuşma yüklenemedi:", error);
      alert("Bu konuşma yüklenemedi. Konsolu kontrol et.");
    } finally {
      setIsLoading(false);
    }
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
      const payload = {
        prompt: messageToSend,
        askAllProviders: true,
      };
      if (conversationId) payload.conversationId = conversationId;

      const response = await axios.post(API_BASE, payload);
      const { conversationId: newConvId, currentMessageId, userMessage, aiResponses } = response.data;

      setConversationId(newConvId);
      setMessages((prev) => [...prev, userMessage, ...aiResponses]);
      setHeadId(currentMessageId);
      setActiveBranchProvider(null);

      fetchConversations();
    } catch (error) {
      console.error("Backend hatası:", error);
      alert("Backend'e ulaşılamadı veya bir hata oluştu. Konsolu kontrol et.");
    } finally {
      setIsLoading(false);
    }
  };

  const handleContinueWith = async (backendProvider) => {
    const selectedAiMessage = getAiMessage(backendProvider);
    if (!selectedAiMessage || !conversationId) {
      alert("Bu sağlayıcıdan henüz bir cevap yok.");
      return;
    }

    try {
      const res = await axios.post(`${API_BASE}/conversations/${conversationId}/select`, {
        messageId: selectedAiMessage.id,
      });
      setHeadId(res.data.currentMessageId);
      setActiveBranchProvider(backendProvider);
    } catch (error) {
      console.error("Dal seçimi başarısız:", error);
      alert("Bu daldan devam edilemedi. Konsolu kontrol et.");
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  return (
    <div className="app-container">
      {/* Sidebar */}
      <div className="sidebar">
        <div className="sidebar-header">
          <span>CompareAI</span>
          <button className="icon-btn" onClick={startNewChat} title="Yeni sohbet">
            <Plus size={16} />
          </button>
        </div>
        <div className="sidebar-history">
          <div className="history-title">Geçmiş Sohbetler</div>
          {conversations.length === 0 ? (
            <div style={{ padding: "10px", fontSize: "0.85rem", color: "#94a3b8" }}>Henüz sohbet yok.</div>
          ) : (
            conversations.map((conv) => (
              <button
                key={conv.id}
                className={`history-item ${conv.id === conversationId ? "active" : ""}`}
                onClick={() => loadConversation(conv.id)}
              >
                <MessageSquare size={14} style={{ marginRight: "8px", flexShrink: 0 }} />
                <span className="history-item-label">{conv.title || `Sohbet #${conv.id}`}</span>
              </button>
            ))
          )}
        </div>
        <div className="sidebar-footer">
          <div className="sidebar-user">{user?.name}</div>
          <button className="icon-btn" onClick={logout} title="Çıkış yap">
            <LogOut size={16} />
          </button>
        </div>
      </div>

      {/* Ana Ekran */}
      <div className="main-content">
        <header className="header">
          <span>
            Yapay zekaları karşılaştır · <strong>{providers.length}</strong> model seçili
          </span>
          <button className="header-edit-btn" onClick={() => navigate("/select")}>
            <Settings2 size={14} /> Seçimi düzenle
          </button>
        </header>

        <div className="input-section">
          <div className="input-box">
            <textarea
              rows="1"
              placeholder="Tüm yapay zekalara aynı anda sor... (Enter ile gönder)"
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

            if (provider.functional) {
              const aiMessage = getAiMessage(provider.backendProvider);
              return (
                <div
                  key={provider.id}
                  className={`ai-card ${isActiveBranch ? "active-branch" : ""}`}
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
                      : "Cevap alınamadı."}
                  </div>
                  <div className="card-footer">
                    <button
                      className="continue-btn"
                      style={{ color: palette.text, borderColor: palette.border }}
                      onClick={() => handleContinueWith(provider.backendProvider)}
                      disabled={!aiMessage}
                    >
                      {provider.name} ile devam et →
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
