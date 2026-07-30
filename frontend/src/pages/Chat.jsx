import { useState, useCallback, useMemo, useEffect, useRef } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Send, Loader2, Plus, Settings2, LogOut, Radio, X, Check, Swords, Quote } from "lucide-react";
import axios from "axios";
import { getProviderById, getProviderByBackendName, CARD_PALETTE, CHAT_MODES } from "../data/aiCatalog";
import { useSelection } from "../context/SelectionContext";
import { useAuth } from "../context/AuthContext";
import { fetchConversation } from "../services/chatApi";

const API_BASE = "http://localhost:8080/api/chat";

// Otomatik Tartışma Modu'nda sağlayıcıların kendi aralarında kaç tur konuşacağı (backend'de
// ChatService#runAutoDebate içinde 2-6 arasına sınırlanıyor, güvenlik için burada da aynı varsayılan).
const DEBATE_ROUNDS = 5;

// Tartışma Modu'nda bir cevabı başka bir sağlayıcıya taşırken önerilen hazır komutlar.
// {providerName} otomatik olarak referans alınan sağlayıcının adıyla değiştirilir.
const DEBATE_QUICK_ACTIONS = [
  { label: "Eleştir", text: "Bu cevabı eleştir: nerede yanlış, eksik veya zayıf gerekçelendirilmiş, açıkça söyle." },
  { label: "Daha teknik açıkla", text: "Aynı konuyu, {providerName}'in cevabından daha teknik ve ayrıntılı açıklar mısın?" },
  { label: "Değerlendir", text: "{providerName}'in bu cevabını değerlendir: katılıyor musun, katılmıyor musun, neden?" },
];

const Chat = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const conversationIdFromUrl = searchParams.get("conversationId");
  const { user, logout } = useAuth();
  const { providers: selectedIds, mode, setSelection } = useSelection();

  const providers = useMemo(() => selectedIds.map(getProviderById).filter(Boolean), [selectedIds]);
  const canStartDebate = providers.length >= 2;

  const [inputText, setInputText] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  // "KISA" | "NORMAL" | "DETAYLI" - her mesajla birlikte backend'e gönderilir (bkz. ChatService#
  // responseLengthHint). Kalıcı değildir, istediğin turda değiştirebilirsin.
  const [responseLength, setResponseLength] = useState("NORMAL");
  // Kaydedilmiş bir sohbet URL'den açılıyorsa, mesajlar backend'den gelene kadar kartlarda
  // "Mesajınızı bekliyor..." yerine bir yükleniyor durumu gösterilir.
  const [isLoadingHistory, setIsLoadingHistory] = useState(!!conversationIdFromUrl);

  // Aktif konuşma durumu
  const [conversationId, setConversationId] = useState(null);
  const [messages, setMessages] = useState([]); // bu konuşmaya ait tüm mesajlar (backend'den düz liste)
  const [headId, setHeadId] = useState(null); // conversation.currentMessageId (HEAD)

  // activeBranchProvider set edildiyse: kullanıcı "X ile devam et" demiş demektir (yalnızca
  // INDEPENDENT modda kullanılır - bkz. handleContinueWith).
  const [activeBranchProvider, setActiveBranchProvider] = useState(null);
  const [branchAnchorId, setBranchAnchorId] = useState(null);

  // COMPARE modunda her kartın kendi mini soru barına yazdığı taslak metin (sağlayıcı bazında).
  const [cardInputs, setCardInputs] = useState({});
  // Hangi sağlayıcıya kart-bazlı (tek hedefli) bir soru gönderiliyor, o an yükleniyor.
  const [loadingCardProvider, setLoadingCardProvider] = useState(null);

  // TARTIŞMA MODU: kullanıcı bir AI balonundaki "Tartışmaya taşı" ikonuna bastığında burası dolar.
  // { provider, providerName, content, messageId } - bir sonraki kart-bazlı gönderimde bu cevap
  // hedef sağlayıcıya AÇIK BİR ALINTI olarak (backend prompt'unun içine gömülü) iletilir.
  const [quotedRef, setQuotedRef] = useState(null);

  const threadRefs = useRef({});

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
  // (Yalnızca INDEPENDENT moddaki "devam et" akışı ve genel HEAD takibi için kullanılıyor.)
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

  // Bir sağlayıcının konuşma boyunca verdiği EN SON cevabı bul (sadece bu turdaki değil).
  const getLatestMessageForProvider = useCallback(
    (backendProvider) => {
      const own = messages.filter((m) => m.role === "ASSISTANT" && m.provider === backendProvider);
      if (own.length === 0) return null;
      return own.reduce((latest, m) => (m.id > latest.id ? m : latest), own[0]);
    },
    [messages]
  );

  // Bir sağlayıcının SMS benzeri sohbet geçmişi: bu sağlayıcının fiilen cevapladığı her soru +
  // kendi cevabı, kronolojik sırayla. Kart-bazlı sadece-bu-sağlayıcıya-sorulan sorular da dahildir;
  // broadcast turlarında diğer sağlayıcılara giden ama bunun cevaplamadığı sorular dahil DEĞİLDİR -
  // böylece her kart tam olarak kendi "gördüğü" konuşmayı, kendi bağımsız mesajlaşma geçmişi
  // gibi gösterir.
  const getProviderThread = useCallback(
    (backendProvider) => {
      const answerByParentId = new Map();
      messages.forEach((m) => {
        if (m.role === "ASSISTANT" && m.provider === backendProvider && m.parentMessageId != null) {
          answerByParentId.set(m.parentMessageId, m);
        }
      });

      const userMessages = messages.filter((m) => m.role === "USER").sort((a, b) => a.id - b.id);

      const thread = [];
      userMessages.forEach((q) => {
        const answer = answerByParentId.get(q.id);
        if (!answer) return;
        thread.push({ question: q, answer });
      });
      return thread;
    },
    [messages]
  );

  // Her kartın sohbet penceresini yeni mesaj geldiğinde en alta kaydır.
  useEffect(() => {
    Object.values(threadRefs.current).forEach((el) => {
      if (el) el.scrollTop = el.scrollHeight;
    });
  }, [messages]);

  // TARTIŞMA MODU: quotedRef doluysa metnin başına o cevabın AÇIK ALINTISını ekler. Hem kart-bazlı
  // gönderimde (handleCardSend) HEM DE üstteki ana bardan gönderimde (handleSendMessage) kullanılır -
  // önceden yalnızca kart gönderiminde uygulanıyordu, bu yüzden üst bardan (ör. Independent modda
  // "X ile devam et" sonrası) gönderilen bir sonraki mesaj alıntıyı tamamen kaybediyordu.
  const wrapWithQuoteIfNeeded = (text, targetBackendProvider) => {
    if (!quotedRef) return text;
    if (targetBackendProvider && quotedRef.provider === targetBackendProvider) return text; // kendi cevabını kendine alıntılamaya gerek yok
    return (
      `[Tartışma Modu - QUOTED_PROVIDER:${quotedRef.provider} - ${quotedRef.providerName}'in cevabını değerlendiriyorsun]\n` +
      `${quotedRef.providerName} şu cevabı verdi:\n"""\n${quotedRef.content}\n"""\n\n` +
      `Bu cevap hakkında kullanıcının isteği: ${text}\n\n` +
      `Yalnızca yukarıdaki alıntılanan cevabı analiz ederek kendi değerlendirmeni (eleştiri, katıldığın/katılmadığın noktalar, karşı argüman veya destekleyici açıklama) yaz.`
    );
  };

  const handleSendMessage = async () => {
    if (!inputText.trim() || isLoading) return;

    const messageToSend = inputText;
    setInputText("");
    setIsLoading(true);

    try {
      const payload = {
        prompt: wrapWithQuoteIfNeeded(messageToSend, activeBranchProvider),
        askAllProviders: activeBranchProvider === null,
        responseLength,
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
      // Alıntı tek kullanımlıktır: hangi yoldan gönderilirse gönderilsin, gönderim başarılı
      // olduğunda temizlenir - aksi halde "Tartışma Modu aktif" bandı yanlışlıkla asılı kalır.
      setQuotedRef(null);
    } catch (error) {
      console.error("Backend hatası:", error);
      alert("Backend'e ulaşılamadı veya bir hata oluştu. Konsolu kontrol et.");
    } finally {
      setIsLoading(false);
    }
  };

  // COMPARE modunda bir kartın kendi mini soru barından gönderilen mesaj: SADECE o sağlayıcıya
  // gider (backend'e targetProvider ile açıkça söyleniyor - bkz. ChatService#sendMessage, COMPARE
  // modunda artık explicit targetProvider varsa tek hedefe izin veriyor).
  //
  // TARTIŞMA MODU: quotedRef doluysa (kullanıcı başka bir AI'nın cevabını "tartışmaya taşı" ile
  // seçtiyse) gönderilen prompt'un başına o cevabın AÇIK ALINTISI eklenir - böylece hedef model
  // yalnızca o belirli cevabı bağlam olarak alıp üzerine eleştiri/karşı görüş/destek üretir.
  const handleCardSend = async (backendProvider, rawText) => {
    const text = (rawText ?? cardInputs[backendProvider] ?? "").trim();
    if (!text || loadingCardProvider || isLoading || !conversationId) return;

    const finalPrompt = wrapWithQuoteIfNeeded(text, backendProvider);

    setCardInputs((prev) => ({ ...prev, [backendProvider]: "" }));
    setLoadingCardProvider(backendProvider);

    try {
      const payload = {
        prompt: finalPrompt,
        conversationId,
        targetProvider: backendProvider,
        responseLength,
      };
      const response = await axios.post(API_BASE, payload);
      const { currentMessageId, userMessage, aiResponses } = response.data;

      setMessages((prev) => [...prev, userMessage, ...aiResponses]);
      setHeadId(currentMessageId);
      // Alıntı tek kullanımlıktır: gönderildikten sonra temizlenir.
      setQuotedRef(null);
    } catch (error) {
      console.error("Karta özel mesaj gönderilemedi:", error);
      alert("Bu sağlayıcıya mesaj gönderilemedi. Konsolu kontrol et.");
    } finally {
      setLoadingCardProvider(null);
    }
  };

  // Bir AI balonundaki "Tartışmaya taşı" ikonuna basıldığında çağrılır.
  const handleQuoteMessage = (backendProvider, providerName, message) => {
    setQuotedRef({ provider: backendProvider, providerName, content: message.content, messageId: message.id });
  };

  // Tartışma Modu hazır komut çipleri: metni ilgili kartın input'una yazar (otomatik göndermez,
  // kullanıcı isterse düzenleyip Enter'a basar).
  const handleQuickDebateAction = (backendProvider, template) => {
    const text = template.replace("{providerName}", quotedRef?.providerName || "diğer yapay zeka");
    setCardInputs((prev) => ({ ...prev, [backendProvider]: text }));
  };

  const handleContinueWith = async (backendProvider) => {
    if (!conversationId) {
      alert("Önce bir mesaj göndermelisin.");
      return;
    }

    if (mode === CHAT_MODES.COMPARE) {
      setActiveBranchProvider(backendProvider);
      setBranchAnchorId(null);
      return;
    }

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

  // COMPARE modu: kullanıcı bir cevabı "tercih ettim" diye işaretler. HEAD'i taşımaz; backend
  // bunu bir sonraki turda TÜM sağlayıcıların göreceği ortak context'e ekler.
  const handlePreferAnswer = async (aiMessage) => {
    if (!conversationId || !aiMessage || isLoading || loadingCardProvider) return;
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

  // OTOMATİK TARTIŞMA MODU: kullanıcı TEK bir soru yazar, "Tartışma Başlat" butonuna basar; backend
  // (bkz. ChatService#runAutoDebate) seçili sağlayıcıları kullanıcı müdahalesi olmadan DEBATE_ROUNDS
  // tur boyunca birbirleriyle konuşturur ve son turda bir moderatör nihai sentezi üretir. Dönen
  // ConversationResponse konuşmanın TAM mesaj listesini içerdiği için - tıpkı geçmiş sohbet
  // yüklemede olduğu gibi - mesajları doğrudan state'e yazıyoruz; her kart, tüm turları (ve nihai
  // sentezi) kendi SMS-benzeri geçmişinde otomatik olarak gösterir, ekstra bir render mantığı gerekmez.
  const handleStartDebate = async () => {
    if (!inputText.trim() || isLoading) return;
    if (!canStartDebate) {
      alert("Otomatik tartışma başlatmak için en az 2 model seçmelisin.");
      return;
    }
    const promptToSend = inputText;
    setInputText("");
    setIsLoading(true);

    try {
      const payload = {
        prompt: promptToSend,
        debateRounds: DEBATE_ROUNDS,
        responseLength,
      };
      if (conversationId) {
        payload.conversationId = conversationId;
      } else {
        payload.providers = selectedIds.map((id) => getProviderById(id)?.backendProvider).filter(Boolean);
        payload.mode = mode;
        payload.userId = user?.id;
      }

      const response = await axios.post(`${API_BASE}/debate`, payload);
      setConversationId(response.data.id);
      setMessages(response.data.messages || []);
      setHeadId(response.data.currentMessageId);
      setQuotedRef(null);
      setActiveBranchProvider(null);
      setBranchAnchorId(null);
    } catch (error) {
      console.error("Otomatik tartışma başlatılamadı:", error);
      alert("Otomatik tartışma başlatılamadı. Konsolu kontrol et.");
    } finally {
      setIsLoading(false);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  const handleCardKeyDown = (e, backendProvider) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleCardSend(backendProvider);
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
                Her mesaj otomatik olarak tüm seçili yapay zekalara gidiyor. Her kartın kendi mini
                soru barına yazarsan mesaj SADECE o karta gider. Bir AI balonunun üstündeki{" "}
                <Swords size={11} style={{ display: "inline", verticalAlign: "-1px" }} /> ikonuyla o
                cevabı <strong>Tartışma Modu</strong>na taşıyıp başka bir yapay zekâya
                değerlendirtebilirsin.
              </span>
            </div>
          )}
          {quotedRef && (
            <div className="branch-banner quote-banner">
              <Quote size={14} />
              <span>
                <strong>Tartışma Modu aktif:</strong> {quotedRef.providerName}'in cevabı, göndereceğin
                bir sonraki kart-mesajına referans olarak eklenecek. "
                {quotedRef.content.slice(0, 90)}
                {quotedRef.content.length > 90 ? "…" : ""}"
              </span>
              <button className="branch-banner-close" onClick={() => setQuotedRef(null)}>
                <X size={13} /> İptal
              </button>
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
          <div className="input-toolbar-row">
            <div className="response-length-picker">
              <span className="response-length-label">Yanıt uzunluğu:</span>
              {[
                { value: "KISA", label: "Kısa" },
                { value: "NORMAL", label: "Normal" },
                { value: "DETAYLI", label: "Detaylı" },
              ].map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  className={`response-length-chip ${responseLength === opt.value ? "active" : ""}`}
                  onClick={() => setResponseLength(opt.value)}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>
          <div className="input-box">
            <div className="input-box-textarea-wrap">
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
            {mode === CHAT_MODES.COMPARE && (
              <button
                className="debate-start-btn"
                onClick={handleStartDebate}
                disabled={isLoading || isLoadingHistory || !inputText.trim() || !canStartDebate}
                title={
                  canStartDebate
                    ? `Bu soruyu sor, sonra ${providers.length} model kendi aralarında ${DEBATE_ROUNDS} tur otomatik tartışsın, sana nihai sonucu getirsinler`
                    : "Otomatik tartışma başlatmak için en az 2 model seçmelisin"
                }
              >
                {isLoading ? <Loader2 size={16} className="animate-spin" /> : <Swords size={16} />}
                Tartışma Başlat ({DEBATE_ROUNDS} tur)
              </button>
            )}
          </div>
        </div>

        {/* AI Kartları */}
        <div className="cards-container">
          {providers.map((provider, index) => {
            const palette = CARD_PALETTE[index % CARD_PALETTE.length];
            const isActiveBranch = activeBranchProvider === provider.backendProvider;
            const isMutedThisTurn = activeBranchProvider !== null && !isActiveBranch;

            const latestMessage = getLatestMessageForProvider(provider.backendProvider);
            const hasAnyHistory = !!latestMessage;
            const canContinueWith = hasAnyHistory;
            const thread = getProviderThread(provider.backendProvider);
            const isCardLoading = loadingCardProvider === provider.backendProvider;
            const isQuoteTarget = quotedRef && quotedRef.provider !== provider.backendProvider;

            return (
              <div
                key={provider.id}
                className={`ai-card ${isActiveBranch ? "active-branch" : ""} ${isMutedThisTurn ? "muted-card" : ""} ${isQuoteTarget ? "quote-target" : ""}`}
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

                {/* Kartın kendi bağımsız sohbet geçmişi - SMS benzeri baloncuklar */}
                <div
                  className="card-thread"
                  ref={(el) => {
                    threadRefs.current[provider.backendProvider] = el;
                  }}
                >
                  {isLoadingHistory ? (
                    <div className="card-thread-empty">Sohbet geçmişi yükleniyor...</div>
                  ) : thread.length === 0 ? (
                    <div className="card-thread-empty">
                      {isMutedThisTurn
                        ? `Bu turda soru sorulmadı (şu an yalnızca ${activeBranchDefinition?.name} ile konuşuluyor).`
                        : "Mesajınızı bekliyor..."}
                    </div>
                  ) : (
                    thread.map(({ question, answer }) => (
                      <div className="thread-turn" key={question.id + "-" + answer.id}>
                        <div className="bubble bubble-user">{question.content}</div>
                        <div
                          className={`bubble bubble-ai ${answer.selected ? "bubble-ai-selected" : ""}`}
                          style={{ borderColor: palette.border }}
                        >
                          {answer.content}
                          <div className="bubble-actions">
                            {answer.selected && (
                              <span className="bubble-selected-tag">
                                <Check size={11} /> Tercih edildi
                              </span>
                            )}
                            <button
                              className="bubble-quote-btn"
                              title={`Bu cevabı Tartışma Modu'na taşı (başka bir AI'ya değerlendirt)`}
                              onClick={() => handleQuoteMessage(provider.backendProvider, provider.name, answer)}
                            >
                              <Swords size={12} /> Tartışmaya taşı
                            </button>
                          </div>
                        </div>
                      </div>
                    ))
                  )}
                </div>

                <div className="card-footer">
                  {mode === CHAT_MODES.COMPARE ? (
                    <>
                      {isQuoteTarget && (
                        <div className="quick-actions-row">
                          {DEBATE_QUICK_ACTIONS.map((qa) => (
                            <button
                              key={qa.label}
                              className="quick-action-chip"
                              onClick={() => handleQuickDebateAction(provider.backendProvider, qa.text)}
                            >
                              {qa.label}
                            </button>
                          ))}
                        </div>
                      )}
                      <div className="card-input-bar">
                        <input
                          type="text"
                          className="card-input"
                          placeholder={
                            isQuoteTarget
                              ? `${quotedRef.providerName}'in cevabı hakkında ${provider.name}'e sor...`
                              : `${provider.name}'e sadece bunu sor...`
                          }
                          value={cardInputs[provider.backendProvider] || ""}
                          onChange={(e) =>
                            setCardInputs((prev) => ({ ...prev, [provider.backendProvider]: e.target.value }))
                          }
                          onKeyDown={(e) => handleCardKeyDown(e, provider.backendProvider)}
                          disabled={!conversationId || isCardLoading}
                        />
                        <button
                          className="card-mini-btn card-send-btn"
                          title={`Sadece ${provider.name}'e gönder`}
                          onClick={() => handleCardSend(provider.backendProvider)}
                          disabled={
                            !conversationId || isCardLoading || !(cardInputs[provider.backendProvider] || "").trim()
                          }
                        >
                          {isCardLoading ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />}
                        </button>
                        <button
                          className={`card-mini-btn card-prefer-btn ${latestMessage?.selected ? "preferred" : ""}`}
                          title="Bu cevabı tercih et"
                          onClick={() => handlePreferAnswer(latestMessage)}
                          disabled={!latestMessage || isLoading || isCardLoading}
                        >
                          <Check size={15} />
                        </button>
                      </div>
                    </>
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
