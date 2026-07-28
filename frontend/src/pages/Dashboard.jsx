import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Plus,
  Sparkles,
  LogOut,
  User,
  Clock,
  MessageSquare,
  AlertCircle,
  Pencil,
  Trash2,
  Check,
  X,
} from "lucide-react";
import { useAuth } from "../context/AuthContext";
import { fetchConversations, renameConversation, deleteConversation } from "../services/chatApi";
import { getProviderByBackendName, CHAT_MODES } from "../data/aiCatalog";

// "5 dakika önce", "2 saat önce" gibi göreli zaman metni üretir.
function formatRelativeTime(isoDateString) {
  if (!isoDateString) return "";
  const date = new Date(isoDateString);
  const diffMs = Date.now() - date.getTime();
  const diffMinutes = Math.round(diffMs / 60000);

  if (diffMinutes < 1) return "az önce";
  if (diffMinutes < 60) return `${diffMinutes} dakika önce`;
  const diffHours = Math.round(diffMinutes / 60);
  if (diffHours < 24) return `${diffHours} saat önce`;
  const diffDays = Math.round(diffHours / 24);
  if (diffDays < 7) return `${diffDays} gün önce`;
  return date.toLocaleDateString("tr-TR", { day: "numeric", month: "short", year: "numeric" });
}

const Dashboard = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [conversations, setConversations] = useState([]);
  const [historyState, setHistoryState] = useState("loading"); // loading | ready | error

  // Şu an başlığı düzenlenen konuşmanın id'si ve o input'un geçici metni.
  const [editingId, setEditingId] = useState(null);
  const [editingTitle, setEditingTitle] = useState("");
  const [savingEdit, setSavingEdit] = useState(false);
  const [deletingId, setDeletingId] = useState(null);

  useEffect(() => {
    if (!user?.id) return;

    let isCancelled = false;
    setHistoryState("loading");

    fetchConversations(user.id)
      .then((data) => {
        if (isCancelled) return;
        setConversations(data);
        setHistoryState("ready");
      })
      .catch((error) => {
        console.error("Sohbet geçmişi alınamadı:", error);
        if (isCancelled) return;
        setHistoryState("error");
      });

    return () => {
      isCancelled = true;
    };
  }, [user?.id]);

  const openConversation = (conversationId) => {
    navigate(`/chat?conversationId=${conversationId}`);
  };

  const startEditing = (conversation) => {
    setEditingId(conversation.id);
    setEditingTitle(conversation.title);
  };

  const cancelEditing = () => {
    setEditingId(null);
    setEditingTitle("");
  };

  const confirmEditing = async (conversationId) => {
    const trimmed = editingTitle.trim();
    if (!trimmed || savingEdit) return;

    setSavingEdit(true);
    try {
      const updated = await renameConversation(conversationId, trimmed);
      setConversations((prev) =>
        prev.map((c) => (c.id === conversationId ? { ...c, title: updated.title } : c))
      );
      cancelEditing();
    } catch (error) {
      console.error("Başlık güncellenemedi:", error);
      alert("Başlık güncellenemedi. Konsolu kontrol et.");
    } finally {
      setSavingEdit(false);
    }
  };

  const handleDelete = async (conversation) => {
    const confirmed = window.confirm(`"${conversation.title}" adlı sohbeti silmek istediğine emin misin?`);
    if (!confirmed) return;

    setDeletingId(conversation.id);
    try {
      await deleteConversation(conversation.id);
      setConversations((prev) => prev.filter((c) => c.id !== conversation.id));
    } catch (error) {
      console.error("Sohbet silinemedi:", error);
      alert("Sohbet silinemedi. Konsolu kontrol et.");
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="dashboard-page">
      <header className="dashboard-header">
        <div className="auth-brand">
          <div className="auth-brand-icon">
            <Sparkles size={20} />
          </div>
          <span>CompareAI</span>
        </div>
        <div className="dashboard-header-right">
          <span className="setup-user">
            <User size={14} style={{ marginRight: 6, verticalAlign: "-2px" }} />
            {user?.name}
          </span>
          <button className="icon-btn" onClick={logout} title="Çıkış yap">
            <LogOut size={16} />
          </button>
        </div>
      </header>

      <main className="dashboard-main">
        <h1>Merhaba{user?.name ? `, ${user.name}` : ""} </h1>
        <p className="dashboard-subtitle">
          Yeni bir sohbet başlatarak istediğin yapay zekaları karşılaştırmaya başlayabilirsin.
        </p>

        <button className="dashboard-new-chat" onClick={() => navigate("/new-chat")}>
          <Plus size={20} />
          Yeni Sohbet
        </button>

        <section className="dashboard-history">
          <div className="dashboard-history-title">
            <Clock size={14} />
            Sohbet Geçmişi
          </div>

          {historyState === "loading" && (
            <div className="dashboard-history-empty">Sohbet geçmişi yükleniyor...</div>
          )}

          {historyState === "error" && (
            <div className="dashboard-history-empty dashboard-history-error">
              <AlertCircle size={16} />
              Sohbet geçmişi alınamadı. Backend'in çalıştığından emin olup sayfayı yenile.
            </div>
          )}

          {historyState === "ready" && conversations.length === 0 && (
            <div className="dashboard-history-empty">
              Henüz bir sohbetin yok. "Yeni Sohbet" ile ilkini başlat.
            </div>
          )}

          {historyState === "ready" && conversations.length > 0 && (
            <ul className="dashboard-history-list">
              {conversations.map((conversation) => {
                const providerDefs = (conversation.providers || [])
                  .map(getProviderByBackendName)
                  .filter(Boolean);
                const isEditing = editingId === conversation.id;
                const isDeleting = deletingId === conversation.id;

                return (
                  <li key={conversation.id}>
                    <div className={`dashboard-history-item ${isEditing ? "editing" : ""}`}>
                      <span className="dashboard-history-item-icon">
                        <MessageSquare size={16} />
                      </span>

                      {isEditing ? (
                        <div className="dashboard-history-item-edit">
                          <input
                            autoFocus
                            value={editingTitle}
                            onChange={(e) => setEditingTitle(e.target.value)}
                            onKeyDown={(e) => {
                              if (e.key === "Enter") confirmEditing(conversation.id);
                              if (e.key === "Escape") cancelEditing();
                            }}
                            disabled={savingEdit}
                          />
                          <button
                            type="button"
                            className="dashboard-history-action confirm"
                            title="Kaydet"
                            onClick={() => confirmEditing(conversation.id)}
                            disabled={savingEdit}
                          >
                            <Check size={15} />
                          </button>
                          <button
                            type="button"
                            className="dashboard-history-action"
                            title="Vazgeç"
                            onClick={cancelEditing}
                            disabled={savingEdit}
                          >
                            <X size={15} />
                          </button>
                        </div>
                      ) : (
                        <>
                          <button
                            type="button"
                            className="dashboard-history-item-main"
                            onClick={() => openConversation(conversation.id)}
                          >
                            <span className="dashboard-history-item-title">{conversation.title}</span>
                            <span className="dashboard-history-item-meta">
                              {formatRelativeTime(conversation.createdAt)}
                              {providerDefs.length > 0 && (
                                <>
                                  {" · "}
                                  {providerDefs.map((p) => p.name).join(", ")}
                                </>
                              )}
                              {conversation.mode === CHAT_MODES.COMPARE && " · Compare"}
                            </span>
                          </button>

                          <div className="dashboard-history-item-actions">
                            <button
                              type="button"
                              className="dashboard-history-action"
                              title="Adını düzenle"
                              onClick={() => startEditing(conversation)}
                            >
                              <Pencil size={14} />
                            </button>
                            <button
                              type="button"
                              className="dashboard-history-action danger"
                              title="Sil"
                              onClick={() => handleDelete(conversation)}
                              disabled={isDeleting}
                            >
                              <Trash2 size={14} />
                            </button>
                          </div>
                        </>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      </main>
    </div>
  );
};

export default Dashboard;
