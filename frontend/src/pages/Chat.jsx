import { useState, useCallback, useMemo, useEffect, useRef } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Send, Loader2, Plus, Settings2, LogOut, Radio, X } from "lucide-react";
import axios from "axios";
import { getProviderById, getProviderByBackendName, CARD_PALETTE, CHAT_MODES } from "../data/aiCatalog";
import { useSelection } from "../context/SelectionContext";
import { useAuth } from "../context/AuthContext";
import { fetchConversation } from "../services/chatApi";

const API_BASE = "http://localhost:8080/api/chat";

const Chat = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const conversationIdFromUrl = searchParams.get("conversationId");
  const { user, logout } = useAuth();
  const { providers: selectedIds, mode, setSelection } = useSelection();

  const providers = useMemo(() => selectedIds.map(getProviderById).filter(Boolean), [selectedIds]);

  const [inputText, setInputText] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  // Kaydedilmiş bir sohbet URL'den açılıyorsa, mesajlar backend'den gelene kadar kartlarda
  // "Mesajınızı bekliyor..." yerine bir yükleniyor durumu gösterilir.
  const [isLoadingHistory, setIsLoadingHistory] = useState(!!conversationIdFromUrl);

  // Aktif konuşma durumu
  const [conversationId, setConversationId] = useState(null);
  const [messages, setMessages] = useState([]); // bu konuşmaya ait tüm mesajlar (backend'den düz liste)
  const [headId, setHeadId] = useState(null); // conversation.currentMessageId (HEAD)

  // activeBranchProvider set edildiyse: kullanıcı "X ile devam et" demiş demektir.
  // Bu durumda üstteki kutudan gönderilen bir sonraki mesaj SADECE o sağlayıcıya gider
  // (backend'e askAllProviders:false gönderilir). "Tümüne dön" ile bu mod kapatılır.
  const [activeBranchProvider, setActiveBranchProvider] = useState(null);

  // Tek sağlayıcı modunda bir sonraki mesajın parent'ı olacak mesaj id'si (her zaman
  // o sağlayıcının EN SON verdiği ASSISTANT cevabı).
  const [branchAnchorId, setBranchAnchorId] = useState(null);

  const activeBranchDefinition = useMemo(
    () => providers.find((p) => p.backendProvider === activeBranchProvider),
    [providers, activeBranchProvider]
  );

  // Bu efekt yalnızca sayfa "?conversationId=..." ile açıldığında (Dashboard'daki geçmişten
  // tıklandığında) bir kez çalışır: konuşmayı tüm mesajlarıyla getirir, kartların doğru
  // sağlayıcılarla/moda göre çizilmesi için SelectionContext'i o konuşmanın kendi
  // providers/mode bilgisiyle günceller ve HEAD'i (currentMessageId) ayarlar.
  const didLoadHistoryRef = useRef(false);
  useEffect(() => {
    if (!conversationIdFromUrl || didLoadHistoryRef.current) return;
    didLoadHistoryRef.current = true;

    (async () => {
      try {
        const conversation = await fetchConversation(conversationIdFromUrl);
        const restoredProviderIds = (conversation.providers || [])
          .map((backendName) => getProviderByBackendName(backendName)?.id)
          .filter(Boolean);

        setSelection(restoredProviderIds, conversation.mode);
        setConversationId(conversation.id);
        setMessages(conversation.messages || []);
        setHeadId(conversation.currentMessageId);
      } catch (error) {
        console.error("Sohbet geçmişi yüklenemedi:", error);
        alert("Bu sohbet yüklenemedi, dashboard'a yönlendiriliyorsun.");
        navigate("/dashboard");
      } finally {
        setIsLoadingHistory(false);
      }
    })();
  }, [conversationIdFromUrl, setSelection, navigate]);

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
  const getLatestMessageForProvider = useCallback(
    (backendProvider) => {
      const own = messages.filter((m) => m.role === "ASSISTANT" && m.provider === backendProvider);
      if (own.length === 0) return null;
      return own.reduce((latest, m) => (m.id > latest.id ? m : latest), own[0]);
    },
    [messages]
  );

  const handleSendMessage = async () => {
    if (!inputText.trim() || isLoading) return;

    const messageToSend = inputText;
    setInputText("");
    setIsLoading(true);

    try {
      const payload = {
        prompt: messageToSend,
        askAllProviders: activeBranchProvider === null,
      };
      if (conversationId) {
        payload.conversationId = conversationId;
      } else {
        // Konuşma ilk kez açılıyor: Yeni Sohbet ekranında seçilen ayarları backend'e ilet,
        // böylece Conversation bu bilgilerle oluşturulur.
        payload.providers = selectedIds.map((id) => getProviderById(id)?.backendProvider).filter(Boolean);
        payload.mode = mode;
        payload.userId = user?.id;
      }
      if (activeBranchProvider !== null) {
        // Hedef sağlayıcıyı backend'e AÇIKÇA söylüyoruz (bkz. ChatRequest#targetProvider).
        // Compare modunda branchAnchorId olmayabilir (bkz. handleContinueWith) - bu durumda
        // backend zaten konuşmanın mevcut HEAD'inden devam eder, o yüzden parentMessageId'yi
        // yalnızca elimizde varsa gönderiyoruz.
        payload.targetProvider = activeBranchProvider;
        if (branchAnchorId) {
          payload.parentMessageId = branchAnchorId;
        }
      }

      const response = await axios.post(API_BASE, payload);
      const { conversationId: newConvId, currentMessageId, userMessage, aiResponses } = response.data;

      setConversationId(newConvId);
      setMessages((prev) => [...prev, userMessage, ...aiResponses]);
      setHeadId(currentMessageId);
      if (activeBranchProvider !== null && aiResponses.length === 1) {
        setBranchAnchorId(aiResponses[0].id);
      }
    } catch (error) {
      console.error("Backend hatası:", error);
      alert("Backend'e ulaşılamadı veya bir hata oluştu. Konsolu kontrol et.");
    } finally {
      setIsLoading(false);
    }
  };

  const handleContinueWith = async (backendProvider) => {
    if (!conversationId) {
      alert("Önce bir mesaj göndermelisin.");
      return;
    }

    // COMPARE modunda sağlayıcılar arasında geçiş yapmak konuşmanın ORTAK bağlamını KORUR:
    // HEAD'i o sağlayıcının kendi eski cevabına atlatmıyoruz (bu, diğer sağlayıcılarla olan
    // geçmişi kaybettirirdi). Bunun yerine mevcut HEAD'den devam ederiz, sadece bundan sonraki
    // mesajın kime gideceğini değiştiririz - backend zaten (Compare modda) her kullanıcı
    // sorusuna verilen tüm cevapları birleştirip context'e koyuyor (bkz. ChatService#buildContext).
    if (mode === CHAT_MODES.COMPARE) {
      setActiveBranchProvider(backendProvider);
      setBranchAnchorId(null);
      return;
    }

    // INDEPENDENT modda ise her sağlayıcı yalnızca KENDİ geçmişini görmeli, o yüzden klasik
    // "checkout": o sağlayıcının en son kendi cevabına dal atlıyoruz.
    const latestMessage = getLatestMessageForProvider(backendProvider);
    if (!latestMessage) {
      alert("Bu sağlayıcıdan henüz bir cevap yok.");
      return;
    }

    try {
      const res = await axios.post(`${API_BASE}/conversations/${conversationId}/select`, {
        messageId: latestMessage.id,
      });
      setHeadId(res.data.currentMessageId);
      setActiveBranchProvider(backendProvider);
      setBranchAnchorId(latestMessage.id);
    } catch (error) {
      console.error("Dal seçimi başarısız:", error);
      alert("Bu daldan devam edilemedi. Konsolu kontrol et.");
    }
  };

  const handleReturnToBroadcast = () => {
    setActiveBranchProvider(null);
    setBranchAnchorId(null);
  };

  // COMPARE modu: kullanıcı bir turdaki cevaplardan birini "tercih ettim" diye işaretler.
  // Bu, HEAD'i taşımaz ve bundan sonraki mesajın kime gideceğini DEĞİŞTİRMEZ - hep üçüne
  // birden gider (bkz. handleSendMessage). Sadece bu tercihi backend'e kaydeder; backend
  // bir sonraki turda bunu TÜM sağlayıcıların göreceği ortak context'e ekler
  // (bkz. ChatService#buildContext / COMPARE_SYSTEM_HINT).
  const handlePreferAnswer = async (aiMessage) => {
    if (!conversationId || !aiMessage || isLoading) return;
    try {
      const res = await axios.post(`${API_BASE}/conversations/${conversationId}/prefer`, {
        messageId: aiMessage.id,
      });
      const updatedById = new Map(res.data.map((m) => [m.id, m]));
      setMessages((prev) => prev.map((m) => (updatedById.has(m.id) ? updatedById.get(m.id) : m)));
    } catch (error) {
      console.error("Tercih kaydedilemedi:", error);
      alert("Tercih kaydedilemedi. Konsolu kontrol et.");
    }
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
                      {" · "}
                      <strong>
              {mode === CHAT_MODES.COMPARE ? "Compare Chat" : "Independent Chat"}
            </strong>
          </span>
          <button className="icon-btn" onClick={() => navigate("/new-chat")} title="Yeni sohbet">
            <Plus size={16} />
          </button>
        </div>
        <div className="header-right">
          <span className="setup-user">{user?.name}</span>
          <button className="header-edit-btn" onClick={() => navigate("/new-chat")}>
            <Settings2 size={14} /> Sohbet ayarları
          </button>
          <button className="icon-btn" onClick={logout} title="Çıkış yap">
            <LogOut size={16} />
          </button>
        </div>
      </header>

      <div className="main-content">
        <div className="input-section">
          {mode === CHAT_MODES.COMPARE && (
            <div className="branch-banner compare-banner">
              <Radio size={14} />
              <span>
                Her mesaj otomatik olarak tüm seçili yapay zekalara gidiyor. Bir cevabı beğendiğinde
                kartın altındaki <strong>"Bu cevabı tercih et"</strong> butonuna basabilirsin — sonraki
                turda hepsi bu tercihini görür.
              </span>
            </div>
          )}
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
              disabled={isLoading || isLoadingHistory}
            />
            <button className="send-btn" onClick={handleSendMessage} disabled={isLoading || isLoadingHistory}>
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

            const aiMessage = getAiMessage(provider.backendProvider);
            const hasAnyHistory = !!getLatestMessageForProvider(provider.backendProvider);
            const canContinueWith = hasAnyHistory;
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
                </div>
                <div className="card-body">
                  {isLoadingHistory
                    ? "Sohbet geçmişi yükleniyor..."
                    : !currentTurn.userMessage
                    ? "Mesajınızı bekliyor..."
                    : aiMessage
                    ? aiMessage.content
                    : isMutedThisTurn
                    ? `Bu turda soru sorulmadı (şu an yalnızca ${activeBranchDefinition?.name} ile konuşuluyor).`
                    : "Cevap alınamadı."}
                </div>
                <div className="card-footer">
                  {mode === CHAT_MODES.COMPARE ? (
                    <button
                      className={`prefer-btn ${aiMessage?.selected ? "preferred" : ""}`}
                      style={{ color: palette.text, borderColor: palette.border }}
                      onClick={() => handlePreferAnswer(aiMessage)}
                      disabled={!aiMessage || isLoading}
                    >
                      {aiMessage?.selected ? "✓ Tercih edildi" : "Bu cevabı tercih et"}
                    </button>
                  ) : (
                    <button
                      className="continue-btn"
                      style={{ color: palette.text, borderColor: palette.border }}
                      onClick={() => handleContinueWith(provider.backendProvider)}
                      disabled={!canContinueWith || isActiveBranch}
                    >
                      {isActiveBranch ? "Şu an bu sağlayıcıdasın" : `${provider.name} ile devam et →`}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default Chat;
