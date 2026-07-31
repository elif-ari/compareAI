import { useState, useCallback, useMemo, useEffect, useRef } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Send, Loader2, Plus, Settings2, LogOut, Radio, X, Check, Swords, Quote, Square, Copy, RotateCcw, Maximize2, Minimize2, GitCompare, Sparkles, Download, FileText, Printer } from "lucide-react";
import axios from "axios";
import { getProviderById, getProviderByBackendName, CARD_PALETTE, CHAT_MODES } from "../data/aiCatalog";
import { useSelection } from "../context/SelectionContext";
import { useAuth } from "../context/AuthContext";
import { fetchConversation } from "../services/chatApi";
import { streamChatRequest } from "../services/sseStream";
import MarkdownRenderer from "../components/MarkdownRenderer";

const API_BASE = "http://localhost:8080/api/chat";

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
  // Otomatik Tartışma Modu'nda turların kaç kez döneceği (2-6 arası, varsayılan 5).
  const [debateRounds, setDebateRounds] = useState(5);
  // Kaydedilmiş bir sohbet URL'den açılıyorsa, mesajlar backend'den gelene kadar kartlarda
  // "Mesajınızı bekliyor..." yerine bir yükleniyor durumu gösterilir.
  const [isLoadingHistory, setIsLoadingHistory] = useState(!!conversationIdFromUrl);

  // STREAMING: şu an cevabı beklenen (henüz gelmemiş) tur. Her sağlayıcının cevabı SSE ile
  // ayrı ayrı geldiği için (bkz. handleSendMessage/handleStartDebate + services/sseStream.js),
  // hangi kartların hâlâ "yazıyor..." göstermesi gerektiğini bu state üzerinden takip ediyoruz.
  // { userMessage: {id, content}, expectedProviders: Set<backendProvider> } | null
  const [pendingTurn, setPendingTurn] = useState(null);
  // Otomatik Tartışma Modu'nda canlı ilerleme göstergesi ("Tur 3/5 sürüyor...").
  const [debateProgress, setDebateProgress] = useState(null); // { round, totalRounds } | null

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
  const [copiedMessageId, setCopiedMessageId] = useState(null);
  const [expandedProviderId, setExpandedProviderId] = useState(null);
  const [turnComparisonLoading, setTurnComparisonLoading] = useState(false);

  const latestUserMessage = useMemo(() => {
    return [...messages].reverse().find((m) => m.role === "USER");
  }, [messages]);

  const currentTurnComparison = useMemo(() => {
    if (!latestUserMessage) return null;
    return messages.find(
      (m) =>
        m.role === "ASSISTANT" &&
        m.parentMessageId === latestUserMessage.id &&
        m.content &&
        m.content.startsWith("[KARŞILAŞTIRMA VE FARKLAR]")
    );
  }, [messages, latestUserMessage]);

  const handleGenerateComparison = async (userMsgId) => {
    if (!conversationId || !userMsgId || turnComparisonLoading) return;
    setTurnComparisonLoading(true);
    try {
      const res = await axios.post(`${API_BASE}/conversations/${conversationId}/turns/${userMsgId}/compare`);
      setMessages((prev) => {
        const exists = prev.some((m) => m.id === res.data.id);
        return exists ? prev.map((m) => (m.id === res.data.id ? res.data : m)) : [...prev, res.data];
      });
    } catch (err) {
      console.error("Karşılaştırma üretilemedi:", err);
    } finally {
      setTurnComparisonLoading(false);
    }
  };

  const [showExportMenu, setShowExportMenu] = useState(false);

  const escapeHtml = (str) => {
    if (!str) return "";
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  };

  const handleExportMarkdown = () => {
    if (!messages || messages.length === 0) {
      alert("Dışa aktarılacak sohbet mesajı bulunamadı.");
      return;
    }

    let mdText = `# CompareAI Sohbet Raporu\n\n`;
    mdText += `**Tarih:** ${new Date().toLocaleString("tr-TR")}\n`;
    mdText += `**Mod:** ${mode === CHAT_MODES.COMPARE ? "Karşılaştırmalı Sohbet & Tartışma" : "Bağımsız Sohbet"}\n`;
    mdText += `**Modeller:** ${providers.map((p) => p.name).join(", ")}\n\n`;
    mdText += `---\n\n`;

    const userMsgs = messages.filter((m) => m.role === "USER");
    userMsgs.forEach((userMsg, idx) => {
      mdText += `## 👤 Tur ${idx + 1}: ${userMsg.content}\n\n`;

      const aiAnswers = messages.filter(
        (m) => m.role === "ASSISTANT" && m.parentMessageId === userMsg.id
      );

      aiAnswers.forEach((aiMsg) => {
        if (aiMsg.content?.startsWith("[KARŞILAŞTIRMA VE FARKLAR]")) {
          mdText += `### ⚡ Cevaplar Arasındaki Temel Farklar & Karşılaştırma\n\n`;
          mdText += `${aiMsg.content.replace("[KARŞILAŞTIRMA VE FARKLAR]\n", "")}\n\n`;
        } else {
          const providerInfo = getProviderByBackendName(aiMsg.provider);
          const pName = providerInfo ? providerInfo.name : aiMsg.provider || "Yapay Zeka";
          mdText += `### 🤖 ${pName}\n\n`;
          mdText += `${aiMsg.content}\n\n`;
        }
      });

      mdText += `---\n\n`;
    });

    const blob = new Blob([mdText], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `CompareAI_Sohbet_${new Date().toISOString().slice(0, 10)}.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const handleExportPDF = () => {
    if (!messages || messages.length === 0) {
      alert("Dışa aktarılacak sohbet mesajı bulunamadı.");
      return;
    }

    const printWindow = window.open("", "_blank");
    if (!printWindow) {
      alert("Yazdırma penceresi açılamadı. Lütfen tarayıcınızın engelleyicisini kontrol edin.");
      return;
    }

    let htmlContent = `
      <!DOCTYPE html>
      <html>
      <head>
        <title>CompareAI Sohbet Raporu</title>
        <style>
          body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; padding: 32px; color: #1e293b; line-height: 1.6; max-width: 900px; margin: 0 auto; }
          h1 { color: #4338ca; border-bottom: 2px solid #e2e8f0; padding-bottom: 12px; margin-top: 0; font-size: 1.8rem; }
          .meta { background: #f8fafc; border: 1px solid #e2e8f0; padding: 14px 18px; border-radius: 10px; font-size: 0.9rem; color: #475569; margin-bottom: 28px; }
          .turn { margin-bottom: 32px; border-bottom: 1px dashed #cbd5e1; padding-bottom: 24px; }
          .user-msg { background: #eef2ff; border-left: 4px solid #4338ca; color: #312e81; padding: 12px 16px; border-radius: 6px; font-weight: 600; margin-bottom: 18px; font-size: 1.05rem; }
          .ai-box { background: #ffffff; border: 1px solid #cbd5e1; border-radius: 10px; padding: 16px 20px; margin-bottom: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.03); }
          .ai-name { font-weight: 700; color: #0f172a; margin-bottom: 8px; font-size: 0.95rem; border-bottom: 1px solid #f1f5f9; padding-bottom: 6px; }
          .comparison-box { background: linear-gradient(135deg, #f0f9ff 0%, #faf5ff 100%); border: 1px solid #c7d2fe; border-radius: 10px; padding: 16px 20px; color: #312e81; margin-top: 20px; }
          .comparison-title { font-weight: 700; color: #4338ca; margin-bottom: 8px; font-size: 1rem; }
          .content-text { white-space: pre-wrap; font-size: 0.92rem; }
        </style>
      </head>
      <body>
        <h1>CompareAI Sohbet Raporu</h1>
        <div class="meta">
          <strong>Tarih:</strong> ${new Date().toLocaleString("tr-TR")}<br/>
          <strong>Sohbet Modu:</strong> ${mode === CHAT_MODES.COMPARE ? "Karşılaştırmalı Sohbet & Tartışma" : "Bağımsız Sohbet"}<br/>
          <strong>Seçili Modeller:</strong> ${providers.map((p) => p.name).join(", ")}
        </div>
    `;

    const userMsgs = messages.filter((m) => m.role === "USER");
    userMsgs.forEach((userMsg, idx) => {
      htmlContent += `<div class="turn">`;
      htmlContent += `<div class="user-msg">👤 Tur ${idx + 1}: ${escapeHtml(userMsg.content)}</div>`;

      const aiAnswers = messages.filter(
        (m) => m.role === "ASSISTANT" && m.parentMessageId === userMsg.id
      );

      aiAnswers.forEach((aiMsg) => {
        if (aiMsg.content?.startsWith("[KARŞILAŞTIRMA VE FARKLAR]")) {
          htmlContent += `
            <div class="comparison-box">
              <div class="comparison-title">⚡ Cevaplar Arasındaki Temel Farklar & Karşılaştırma</div>
              <div class="content-text">${escapeHtml(aiMsg.content.replace("[KARŞILAŞTIRMA VE FARKLAR]\n", ""))}</div>
            </div>
          `;
        } else {
          const providerInfo = getProviderByBackendName(aiMsg.provider);
          const pName = providerInfo ? providerInfo.name : aiMsg.provider || "Yapay Zeka";
          htmlContent += `
            <div class="ai-box">
              <div class="ai-name">🤖 ${pName}</div>
              <div class="content-text">${escapeHtml(aiMsg.content)}</div>
            </div>
          `;
        }
      });

      htmlContent += `</div>`;
    });

    htmlContent += `
        <script>
          window.onload = function() {
            setTimeout(function() {
              window.print();
            }, 300);
          }
        </script>
      </body>
      </html>
    `;

    printWindow.document.write(htmlContent);
    printWindow.document.close();
  };

  const expandedProvider = useMemo(
    () => providers.find((p) => p.id === expandedProviderId),
    [providers, expandedProviderId]
  );

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === "Escape" && expandedProviderId) {
        setExpandedProviderId(null);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [expandedProviderId]);

  const threadRefs = useRef({});
  const abortControllerRef = useRef(null);

  const handleStopStream = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
  }, []);

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
    if (mode === CHAT_MODES.INDEPENDENT || !quotedRef) return text;
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
    const targetProviderForThisTurn = activeBranchProvider;
    const expectedProviders =
      targetProviderForThisTurn !== null
        ? new Set([targetProviderForThisTurn])
        : new Set(providers.map((p) => p.backendProvider));

    const controller = new AbortController();
    abortControllerRef.current = controller;

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

      let lastAiMessageId = null;
      let aiResponseCount = 0;

      await streamChatRequest(`${API_BASE}/stream`, payload, {
        signal: controller.signal,
        onEvent: (eventName, data) => {
          if (eventName === "user_message") {
            setConversationId(data.conversationId);
            setMessages((prev) => [...prev, data.userMessage]);
            setHeadId(data.currentMessageId);
            setPendingTurn({ userMessage: data.userMessage, expectedProviders });
          } else if (eventName === "ai_response") {
            const aiMessage = data.message;
            aiResponseCount += 1;
            lastAiMessageId = aiMessage.id;
            setMessages((prev) => [...prev, aiMessage]);
            setPendingTurn((prev) => {
              if (!prev) return prev;
              const next = new Set(prev.expectedProviders);
              next.delete(aiMessage.provider);
              return next.size === 0 ? null : { ...prev, expectedProviders: next };
            });
          } else if (eventName === "comparison_summary") {
            if (data?.summary) {
              setMessages((prev) => [...prev, data.summary]);
            }
          } else if (eventName === "fatal_error") {
            throw new Error(data?.message || "Akış sırasında beklenmedik bir hata oluştu.");
          }
        },
      });

      if (targetProviderForThisTurn !== null && aiResponseCount === 1 && lastAiMessageId) {
        setBranchAnchorId(lastAiMessageId);
      }
      setQuotedRef(null);
    } catch (error) {
      if (error.name === "AbortError" || controller.signal.aborted) {
        console.log("Yanıt üretimi kullanıcı tarafından durduruldu.");
      } else {
        console.error("Backend hatası:", error);
        alert("Backend'e ulaşılamadı veya bir hata oluştu. Konsolu kontrol et.");
      }
    } finally {
      abortControllerRef.current = null;
      setIsLoading(false);
      setPendingTurn(null);
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
    const controller = new AbortController();
    abortControllerRef.current = controller;

    setCardInputs((prev) => ({ ...prev, [backendProvider]: "" }));
    setLoadingCardProvider(backendProvider);

    try {
      const payload = {
        prompt: finalPrompt,
        conversationId,
        targetProvider: backendProvider,
        responseLength,
      };
      const response = await axios.post(API_BASE, payload, { signal: controller.signal });
      const { currentMessageId, userMessage, aiResponses } = response.data;

      setMessages((prev) => [...prev, userMessage, ...aiResponses]);
      setHeadId(currentMessageId);
      setQuotedRef(null);
    } catch (error) {
      if (axios.isCancel(error) || error.name === "AbortError" || controller.signal.aborted) {
        console.log("Karta özel mesaj durduruldu.");
      } else {
        console.error("Karta özel mesaj gönderilemedi:", error);
        alert("Bu sağlayıcıya mesaj gönderilemedi. Konsolu kontrol et.");
      }
    } finally {
      abortControllerRef.current = null;
      setLoadingCardProvider(null);
    }
  };

  const handleCopyAnswer = async (messageId, content) => {
    try {
      await navigator.clipboard.writeText(content);
      setCopiedMessageId(messageId);
      setTimeout(() => setCopiedMessageId(null), 2000);
    } catch (err) {
      console.error("Cevap kopyalanamadı:", err);
    }
  };

  // Bir AI balonundaki "Tartışmaya taşı" ikonuna basıldığında çağrılır.
  const handleQuoteMessage = (backendProvider, providerName, message) => {
    if (mode === CHAT_MODES.INDEPENDENT) return;
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

  // COMPARE modu: kullanıcı bir cevabı "tercih ettim" diye işaretler (veya tekrar basarak tercihi kaldırır).
  const handlePreferAnswer = async (aiMessage) => {
    if (!conversationId || !aiMessage || isLoading || loadingCardProvider) return;

    const willBeSelected = !aiMessage.selected;

    // Anlık (optimistic) UI güncellemesi: Zaten seçili bir cevapsa hemen kaldır, değilse tercih et
    setMessages((prev) =>
      prev.map((m) => {
        if (m.parentMessageId === aiMessage.parentMessageId && m.role === "ASSISTANT") {
          return { ...m, selected: m.id === aiMessage.id ? willBeSelected : false };
        }
        return m;
      })
    );

    try {
      const res = await axios.post(`${API_BASE}/conversations/${conversationId}/prefer`, {
        messageId: aiMessage.id,
      });
      const updatedById = new Map(res.data.map((m) => [m.id, m]));
      setMessages((prev) =>
        prev.map((m) => {
          if (updatedById.has(m.id)) {
            const backendMsg = updatedById.get(m.id);
            return {
              ...backendMsg,
              selected: !willBeSelected && m.id === aiMessage.id ? false : backendMsg.selected,
            };
          }
          return m;
        })
      );
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
    const expectedProviders = new Set(providers.map((p) => p.backendProvider));
    const moderatorProvider = providers[0]?.backendProvider;

    const controller = new AbortController();
    abortControllerRef.current = controller;

    setInputText("");
    setIsLoading(true);
    setDebateProgress({ round: 1, totalRounds: debateRounds });

    try {
      const payload = {
        prompt: promptToSend,
        debateRounds,
        responseLength,
      };
      if (conversationId) {
        payload.conversationId = conversationId;
      } else {
        payload.providers = selectedIds.map((id) => getProviderById(id)?.backendProvider).filter(Boolean);
        payload.mode = mode;
        payload.userId = user?.id;
      }

      await streamChatRequest(`${API_BASE}/debate/stream`, payload, {
        signal: controller.signal,
        onEvent: (eventName, data) => {
          if (eventName === "conversation_created") {
            setConversationId(data.conversationId);
          } else if (eventName === "turn_message") {
            setDebateProgress({ round: data.round, totalRounds: data.totalRounds });
            setMessages((prev) => [...prev, data.message]);
            setHeadId(data.message.id);
            setPendingTurn({ userMessage: data.message, expectedProviders: new Set(expectedProviders) });
          } else if (eventName === "ai_response") {
            const aiMessage = data.message;
            setMessages((prev) => [...prev, aiMessage]);
            setPendingTurn((prev) => {
              if (!prev) return prev;
              const next = new Set(prev.expectedProviders);
              next.delete(aiMessage.provider);
              return next.size === 0 ? null : { ...prev, expectedProviders: next };
            });
          } else if (eventName === "synthesis_trigger") {
            setMessages((prev) => [...prev, data.message]);
            setHeadId(data.message.id);
            setDebateProgress({ round: debateRounds, totalRounds: debateRounds, synthesis: true });
            if (moderatorProvider) {
              setPendingTurn({ userMessage: data.message, expectedProviders: new Set([moderatorProvider]) });
            }
          } else if (eventName === "synthesis") {
            setMessages((prev) => [...prev, data.message]);
            setPendingTurn(null);
          } else if (eventName === "fatal_error") {
            throw new Error(data?.message || "Tartışma sırasında beklenmedik bir hata oluştu.");
          }
        },
      });

      setQuotedRef(null);
      setActiveBranchProvider(null);
      setBranchAnchorId(null);
    } catch (error) {
      if (error.name === "AbortError" || controller.signal.aborted) {
        console.log("Otomatik tartışma kullanıcı tarafından durduruldu.");
      } else {
        console.error("Otomatik tartışma başlatılamadı:", error);
        alert("Otomatik tartışma başlatılamadı. Konsolu kontrol et.");
      }
    } finally {
      abortControllerRef.current = null;
      setIsLoading(false);
      setPendingTurn(null);
      setDebateProgress(null);
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
          <div className="export-dropdown-wrapper">
            <button
              className="header-export-btn"
              onClick={() => setShowExportMenu((prev) => !prev)}
              title="Sohbeti dışa aktar (PDF / Markdown)"
            >
              <Download size={14} /> Dışa Aktar
            </button>
            {showExportMenu && (
              <div className="export-menu" onClick={() => setShowExportMenu(false)}>
                <button className="export-menu-item" onClick={handleExportMarkdown}>
                  <FileText size={14} /> Markdown (.md) Olarak İndir
                </button>
                <button className="export-menu-item" onClick={handleExportPDF}>
                  <Printer size={14} /> PDF Olarak Yazdır / İndir
                </button>
              </div>
            )}
          </div>
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
          {debateProgress && (
            <div className="branch-banner debate-progress-banner">
              <Swords size={14} className="debate-progress-icon" />
              <span>
                {debateProgress.synthesis
                  ? "Tartışma tamamlandı, nihai sentez hazırlanıyor..."
                  : `Otomatik tartışma sürüyor: Tur ${debateProgress.round}/${debateProgress.totalRounds} - modeller cevap verdikçe kartlar canlı olarak doluyor.`}
              </span>
            </div>
          )}
          {mode === CHAT_MODES.COMPARE && quotedRef && (
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

            {mode === CHAT_MODES.COMPARE && (
              <div className="response-length-picker debate-rounds-picker">
                <span className="response-length-label">Tartışma modu:</span>
                <select
                  className="debate-rounds-select"
                  value={debateRounds}
                  onChange={(e) => setDebateRounds(Number(e.target.value))}
                  title="Tartışma tur sayısını seç"
                >
                  {[2, 3, 4, 5, 6].map((num) => (
                    <option key={num} value={num}>
                      {num} Tur
                    </option>
                  ))}
                </select>
              </div>
            )}
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
              {isLoading || loadingCardProvider ? (
                <button
                  type="button"
                  className="send-btn stop-btn"
                  onClick={handleStopStream}
                  title="Yanıt üretmeyi durdur"
                >
                  <Square size={14} fill="currentColor" /> Durdur
                </button>
              ) : (
                <button
                  type="button"
                  className="send-btn"
                  onClick={handleSendMessage}
                  disabled={isLoadingHistory || !inputText.trim()}
                  title="Gönder"
                >
                  <Send size={18} />
                </button>
              )}
            </div>
            {mode === CHAT_MODES.COMPARE && (
              <button
                className="debate-start-btn"
                onClick={handleStartDebate}
                disabled={isLoading || isLoadingHistory || !inputText.trim() || !canStartDebate}
                title={
                  canStartDebate
                    ? `Bu soruyu sor, sonra ${providers.length} model kendi aralarında ${debateRounds} tur otomatik tartışsın, sana nihai sonucu getirsinler`
                    : "Otomatik tartışma başlatmak için en az 2 model seçmelisin"
                }
              >
                {isLoading ? <Loader2 size={16} className="animate-spin" /> : <Swords size={16} />}
                Tartışma Başlat ({debateRounds} tur)
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
            const isQuoteTarget = mode === CHAT_MODES.COMPARE && quotedRef && quotedRef.provider !== provider.backendProvider;
            // STREAMING: bu kart şu an cevap bekleniyor mu? Öyleyse thread'in sonuna cevapsız
            // (answer: null) bir "tur" ekleyip aşağıda onu bir "yazıyor..." balonu olarak
            // gösteriyoruz - diğer kartlar kendi cevabı geldiğinde bağımsız olarak dolmaya devam eder.
            const isPendingHere = !!(pendingTurn && pendingTurn.expectedProviders.has(provider.backendProvider));
            const displayThread = isPendingHere
              ? [...thread, { question: pendingTurn.userMessage, answer: null }]
              : thread;

            return (
              <div
                key={provider.id}
                className={`ai-card ${isActiveBranch ? "active-branch" : ""} ${isMutedThisTurn ? "muted-card" : ""} ${isQuoteTarget ? "quote-target" : ""}`}
                style={{
                  "--card-color": palette.bg,
                  "--card-border": palette.border,
                  "--card-text": palette.text,
                  "--card-selected-bg": palette.selectedBg,
                  "--card-selected-border": palette.selectedBorder,
                }}
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
                  {isPendingHere && (
                    <span className="card-live-typing" title="Bu model hâlâ cevap yazıyor">
                      <Loader2 size={13} className="animate-spin" />
                    </span>
                  )}
                  <button
                    type="button"
                    className="card-expand-btn"
                    onClick={() => setExpandedProviderId(provider.id)}
                    title="Kartı büyüt (Tam Ekran)"
                  >
                    <Maximize2 size={14} />
                  </button>
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
                  ) : displayThread.length === 0 ? (
                    <div className="card-thread-empty">
                      {isMutedThisTurn
                        ? `Bu turda soru sorulmadı (şu an yalnızca ${activeBranchDefinition?.name} ile konuşuluyor).`
                        : "Mesajınızı bekliyor..."}
                    </div>
                  ) : (
                    displayThread.map(({ question, answer }) => (
                      <div className="thread-turn" key={question.id + "-" + (answer ? answer.id : "pending")}>
                        <div className="bubble bubble-user">{question.content}</div>
                        {answer ? (
                          <div
                            className={`bubble bubble-ai ${answer.selected ? "bubble-ai-selected" : ""}`}
                            style={{ borderColor: palette.border }}
                          >
                            <MarkdownRenderer content={answer.content} />
                            <div className="bubble-actions">
                              {answer.selected && (
                                <span className="bubble-selected-tag">
                                  <Check size={11} /> Tercih edildi
                                </span>
                              )}
                              <button
                                type="button"
                                className="bubble-action-btn"
                                title="Cevabı kopyala"
                                onClick={() => handleCopyAnswer(answer.id, answer.content)}
                              >
                                {copiedMessageId === answer.id ? (
                                  <>
                                    <Check size={11} /> Kopyalandı!
                                  </>
                                ) : (
                                  <>
                                    <Copy size={11} /> Kopyala
                                  </>
                                )}
                              </button>
                              <button
                                type="button"
                                className="bubble-action-btn"
                                title="Aynı soruyu sadece bu modele tekrar sordur"
                                onClick={() => handleCardSend(provider.backendProvider, question.content)}
                                disabled={isLoading || !!loadingCardProvider}
                              >
                                <RotateCcw size={11} /> Tekrar üret
                              </button>
                              {mode === CHAT_MODES.COMPARE && (
                                <button
                                  type="button"
                                  className="bubble-quote-btn"
                                  title={`Bu cevabı Tartışma Modu'na taşı (başka bir AI'ya değerlendirt)`}
                                  onClick={() => handleQuoteMessage(provider.backendProvider, provider.name, answer)}
                                >
                                  <Swords size={11} /> Tartışmaya taşı
                                </button>
                              )}
                            </div>
                          </div>
                        ) : (
                          <div className="bubble bubble-ai bubble-ai-pending" style={{ borderColor: palette.border }}>
                            <span className="typing-dots">
                              <span></span>
                              <span></span>
                              <span></span>
                            </span>
                          </div>
                        )}
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

        {/* COMPARE Modunda: Cevaplar Arasındaki Temel Farklar & Karşılaştırma Kartı */}
        {mode === CHAT_MODES.COMPARE && latestUserMessage && (
          <div className="turn-comparison-wrapper">
            {turnComparisonLoading ? (
              <div className="turn-comparison-card loading">
                <Sparkles size={18} className="animate-spin text-indigo-600" />
                <span>Modellerin cevapları analiz ediliyor ve farklar özetleniyor...</span>
              </div>
            ) : currentTurnComparison ? (
              <div className="turn-comparison-card">
                <div className="turn-comparison-header">
                  <div className="turn-comparison-title">
                    <GitCompare size={18} className="text-indigo-600" />
                    <span>Cevaplar Arasındaki Temel Farklar & Karşılaştırma</span>
                  </div>
                  <button
                    className="turn-comparison-refresh-btn"
                    onClick={() => handleGenerateComparison(latestUserMessage.id)}
                    title="Karşılaştırmayı Yenile"
                  >
                    <RotateCcw size={14} />
                  </button>
                </div>
                <div className="turn-comparison-body">
                  <MarkdownRenderer
                    content={currentTurnComparison.content.replace("[KARŞILAŞTIRMA VE FARKLAR]\n", "")}
                  />
                </div>
              </div>
            ) : (
              <button
                className="generate-comparison-btn"
                onClick={() => handleGenerateComparison(latestUserMessage.id)}
                disabled={isLoading || loadingCardProvider}
              >
                <Sparkles size={16} />
                Cevaplar Arasındaki Farkları Özetle (Karşılaştır)
              </button>
            )}
          </div>
        )}
      </div>

      {/* Fullscreen / Focus Modal */}
      {expandedProvider && (() => {
        const palette = CARD_PALETTE[providers.findIndex((p) => p.id === expandedProvider.id) % CARD_PALETTE.length];
        const thread = getProviderThread(expandedProvider.backendProvider);
        const isPendingHere = !!(pendingTurn && pendingTurn.expectedProviders.has(expandedProvider.backendProvider));
        const displayThread = isPendingHere
          ? [...thread, { question: pendingTurn.userMessage, answer: null }]
          : thread;
        const isCardLoading = loadingCardProvider === expandedProvider.backendProvider;

        return (
          <div className="card-modal-backdrop" onClick={() => setExpandedProviderId(null)}>
            <div
              className="card-modal-container"
              onClick={(e) => e.stopPropagation()}
              style={{
                "--card-color": palette.bg,
                "--card-border": palette.border,
                "--card-text": palette.text,
                "--card-selected-bg": palette.selectedBg,
                "--card-selected-border": palette.selectedBorder,
              }}
            >
              <div className="card-modal-header">
                <div className="card-modal-header-left">
                  <div className="avatar" style={{ background: palette.bg }}>
                    {expandedProvider.vendor[0]}
                  </div>
                  <div>
                    <div className="card-modal-title">
                      <strong>{expandedProvider.name}</strong>
                      <span className="card-modal-badge">{expandedProvider.vendor} · {expandedProvider.detail}</span>
                    </div>
                  </div>
                </div>
                <div className="card-modal-header-right">
                  <button
                    type="button"
                    className="close-modal-btn"
                    onClick={() => setExpandedProviderId(null)}
                    title="Kapat (ESC)"
                  >
                    <Minimize2 size={16} /> Küçült
                  </button>
                </div>
              </div>

              <div className="card-modal-body">
                {isLoadingHistory ? (
                  <div className="card-thread-empty">Sohbet geçmişi yükleniyor...</div>
                ) : displayThread.length === 0 ? (
                  <div className="card-thread-empty">Mesajınızı bekliyor...</div>
                ) : (
                  displayThread.map(({ question, answer }) => (
                    <div className="thread-turn" key={"modal-" + question.id + "-" + (answer ? answer.id : "pending")}>
                      <div className="bubble bubble-user">{question.content}</div>
                      {answer ? (
                        <div
                          className={`bubble bubble-ai ${answer.selected ? "bubble-ai-selected" : ""}`}
                          style={{ borderColor: palette.border }}
                        >
                          <MarkdownRenderer content={answer.content} />
                          <div className="bubble-actions">
                            {answer.selected && (
                              <span className="bubble-selected-tag">
                                <Check size={11} /> Tercih edildi
                              </span>
                            )}
                            <button
                              type="button"
                              className="bubble-action-btn"
                              title="Cevabı kopyala"
                              onClick={() => handleCopyAnswer(answer.id, answer.content)}
                            >
                              {copiedMessageId === answer.id ? (
                                <>
                                  <Check size={11} /> Kopyalandı!
                                </>
                              ) : (
                                <>
                                  <Copy size={11} /> Kopyala
                                </>
                              )}
                            </button>
                            <button
                              type="button"
                              className="bubble-action-btn"
                              title="Aynı soruyu sadece bu modele tekrar sordur"
                              onClick={() => handleCardSend(expandedProvider.backendProvider, question.content)}
                              disabled={isLoading || !!loadingCardProvider}
                            >
                              <RotateCcw size={11} /> Tekrar üret
                            </button>
                            {mode === CHAT_MODES.COMPARE && (
                              <button
                                type="button"
                                className="bubble-quote-btn"
                                title="Bu cevabı Tartışma Modu'na taşı"
                                onClick={() => handleQuoteMessage(expandedProvider.backendProvider, expandedProvider.name, answer)}
                              >
                                <Swords size={11} /> Tartışmaya taşı
                              </button>
                            )}
                          </div>
                        </div>
                      ) : (
                        <div className="bubble bubble-ai bubble-ai-pending" style={{ borderColor: palette.border }}>
                          <span className="typing-dots">
                            <span></span>
                            <span></span>
                            <span></span>
                          </span>
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>

              <div className="card-modal-footer">
                <div className="card-input-bar">
                  <input
                    type="text"
                    className="card-input"
                    placeholder={`${expandedProvider.name}'e özel soru sor... (Enter ile gönder)`}
                    value={cardInputs[expandedProvider.backendProvider] || ""}
                    onChange={(e) =>
                      setCardInputs((prev) => ({ ...prev, [expandedProvider.backendProvider]: e.target.value }))
                    }
                    onKeyDown={(e) => handleCardKeyDown(e, expandedProvider.backendProvider)}
                    disabled={!conversationId || isCardLoading}
                  />
                  <button
                    type="button"
                    className="card-mini-btn card-send-btn"
                    title={`Sadece ${expandedProvider.name}'e gönder`}
                    onClick={() => handleCardSend(expandedProvider.backendProvider)}
                    disabled={!conversationId || isCardLoading || !(cardInputs[expandedProvider.backendProvider] || "").trim()}
                  >
                    {isCardLoading ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />}
                  </button>
                </div>
              </div>
            </div>
          </div>
        );
      })()}
    </div>
  );
};

export default Chat;
