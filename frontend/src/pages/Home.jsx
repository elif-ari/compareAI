import React, { useState, useEffect } from 'react';
import { Send, MessageSquare, Loader2 } from 'lucide-react';
import axios from 'axios';

const Home = () => {
    const [inputText, setInputText] = useState("");
    const [isLoading, setIsLoading] = useState(false);
    const [chatResponse, setChatResponse] = useState(null);

    // Geçmiş sohbetleri tutacağımız state (Artık boş bir dizi ile başlıyor)
    const [conversations, setConversations] = useState([]);

    // Sayfa ilk yüklendiğinde geçmiş sohbetleri backend'den çek
    useEffect(() => {
        fetchConversations();
    }, []);

    const fetchConversations = async () => {
        try {
            const res = await axios.get("http://localhost:8080/api/chat/conversations");
            setConversations(res.data);
        } catch (error) {
            console.error("Geçmiş sohbetler çekilemedi:", error);
        }
    };

    const handleSendMessage = async () => {
        if (!inputText.trim()) return;

        const messageToSend = inputText;
        setInputText("");
        setIsLoading(true);

        try {
            const response = await axios.post("http://localhost:8080/api/chat", {
                prompt: messageToSend,
                askAllProviders: true
            });

            setChatResponse(response.data);
            // Yeni mesaj attıktan sonra sol menüyü güncellemek iyi bir pratiktir
            fetchConversations();

        } catch (error) {
            console.error("Backend hatası:", error);
            alert("Backend'e ulaşılamadı veya bir hata oluştu. Konsolu kontrol et.");
        } finally {
            setIsLoading(false);
        }
    };

    // Kullanıcı bir AI'ın cevabından devam etmek istediğinde çalışacak fonksiyon
    const handleContinueWith = async (providerName) => {
        if (!chatResponse) return;

        // Seçilen AI'ın döndürdüğü spesifik mesaj objesini buluyoruz
        const selectedAiMessage = chatResponse.aiResponses.find(r => r.provider === providerName);

        if (!selectedAiMessage) {
            alert("Bu sağlayıcıdan henüz bir cevap yok.");
            return;
        }

        try {
            // Backend'deki HEAD'i bu mesajın ID'sine taşıyoruz (Branching)
            // Not: SelectMessageRequest DTO'n içindeki alan adını "messageId" varsaydım.
            // Eğer DTO'da "selectedMessageId" veya farklı bir ad varsa, buradaki JSON'u ona göre güncellemelisin.
            await axios.post(`http://localhost:8080/api/chat/conversations/${chatResponse.conversationId}/select`, {
                messageId: selectedAiMessage.id
            });

            alert(`Başarılı! ${providerName} dalına (branch) geçiş yaptın. Bundan sonraki mesajlar bu sohbet üzerinden ilerleyecek.`);

            // İlerleyen aşamalarda burada UI'ı tekli AI görünümüne geçirecek bir state (örn: setActiveBranch) tetikleyebiliriz.

        } catch (error) {
            console.error("Dal seçimi başarısız:", error);
            alert("Bu daldan devam edilemedi. Konsolu kontrol et.");
        }
    };

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSendMessage();
        }
    };

    const getAiContent = (providerName) => {
        if (!chatResponse) return "Mesajınızı bekliyor...";
        const aiMessage = chatResponse.aiResponses.find(r => r.provider === providerName);
        return aiMessage ? aiMessage.content : "Cevap alınamadı.";
    };

    return (
        <div className="app-container">
            {/* Sidebar */}
            <div className="sidebar">
                <div className="sidebar-header">
                    <span>CompareAI</span>
                </div>
                <div className="sidebar-history">
                    <div className="history-title">Geçmiş Sohbetler</div>
                    {conversations.length === 0 ? (
                        <div style={{ padding: '10px', fontSize: '0.85rem', color: '#94a3b8' }}>Henüz sohbet yok.</div>
                    ) : (
                        conversations.map((conv) => (
                            <button key={conv.id} className="history-item">
                                <MessageSquare size={14} style={{ marginRight: '8px' }} />
                                {/* DTO'ndaki alan adına göre conv.title veya conv.summary olarak güncelleyebilirsin */}
                                {conv.title || `Sohbet #${conv.id}`}
                            </button>
                        ))
                    )}
                </div>
            </div>

            {/* Ana Ekran */}
            <div className="main-content">
                <header className="header">
                    <span>Yapay zekaları karşılaştır</span>
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
                        <button
                            className="send-btn"
                            onClick={handleSendMessage}
                            disabled={isLoading}
                        >
                            {isLoading ? (
                                <Loader2 size={18} className="animate-spin" />
                            ) : (
                                <Send size={18} />
                            )}
                        </button>
                    </div>
                </div>

                {/* AI Kartları */}
                <div className="cards-container">
                    {/* Gemini */}
                    <div className="ai-card gemini">
                        <div className="card-header">
                            <div className="avatar">G</div>
                            <div>
                                <strong>Gemini</strong>
                                <div style={{ fontSize: '0.75rem', color: '#94a3b8' }}>Google · 2.0 Flash</div>
                            </div>
                        </div>
                        <div className="card-body">
                            {getAiContent('GEMINI')}
                        </div>
                        <div className="card-footer">
                            <button
                                className="continue-btn"
                                onClick={() => handleContinueWith('GEMINI')}
                            >
                                Gemini ile devam et →
                            </button>
                        </div>
                    </div>

                    {/* Claude */}
                    <div className="ai-card claude">
                        <div className="card-header">
                            <div className="avatar">C</div>
                            <div>
                                <strong>Claude</strong>
                                <div style={{ fontSize: '0.75rem', color: '#94a3b8' }}>Anthropic · Sonnet 4.6</div>
                            </div>
                        </div>
                        <div className="card-body">
                            {getAiContent('CLAUDE')}
                        </div>
                        <div className="card-footer">
                            <button
                                className="continue-btn"
                                onClick={() => handleContinueWith('CLAUDE')}
                            >
                                Claude ile devam et →
                            </button>
                        </div>
                    </div>

                    {/* ChatGPT */}
                    <div className="ai-card chatgpt">
                        <div className="card-header">
                            <div className="avatar">C</div>
                            <div>
                                <strong>ChatGPT</strong>
                                <div style={{ fontSize: '0.75rem', color: '#94a3b8' }}>OpenAI · GPT-4o mini</div>
                            </div>
                        </div>
                        <div className="card-body">
                            {getAiContent('OPENAI')}
                        </div>
                        <div className="card-footer">
                            <button
                                className="continue-btn"
                                onClick={() => handleContinueWith('OPENAI')}
                            >
                                ChatGPT ile devam et →
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default Home;