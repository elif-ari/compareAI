import { useNavigate } from "react-router-dom";
import { Plus, Sparkles, LogOut, User, Clock } from "lucide-react";
import { useAuth } from "../context/AuthContext";

const Dashboard = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

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
        <h1>Merhaba{user?.name ? `, ${user.name}` : ""} 👋</h1>
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
          <div className="dashboard-history-empty">
            Sohbet geçmişi yakında burada listelenecek.
          </div>
        </section>
      </main>
    </div>
  );
};

export default Dashboard;
