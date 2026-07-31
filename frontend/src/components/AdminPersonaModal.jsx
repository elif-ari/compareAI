import { useState, useEffect } from "react";
import { X, Plus, Pencil, Trash2, Check, Sparkles, UserCheck, ShieldAlert } from "lucide-react";
import { fetchPersonas, createPersona, updatePersona, deletePersona } from "../services/personaApi";

const ICON_OPTIONS = [
  { id: "Bot", label: "🤖 Asistan" },
  { id: "Code", label: "💻 Yazılımcı" },
  { id: "GraduationCap", label: "🎓 Öğretmen" },
  { id: "Microscope", label: "🔬 Akademik" },
  { id: "Scale", label: "⚖️ Hukukçu" },
  { id: "Stethoscope", label: "🩺 Doktor" },
  { id: "Film", label: "🎬 Eleştirmen" },
  { id: "Compass", label: "🗺️ Tur Rehberi" },
  { id: "Briefcase", label: "💼 Girişimci" },
  { id: "Dumbbell", label: "🏋️ Fitness Koçu" },
];

export default function AdminPersonaModal({ isOpen, onClose, onPersonasUpdated }) {
  const [personas, setPersonas] = useState([]);
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState("list"); // list | form

  // Form State
  const [editingId, setEditingId] = useState(null);
  const [formData, setFormData] = useState({
    name: "",
    title: "",
    description: "",
    systemPrompt: "",
    icon: "Bot",
    isDefault: false,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (isOpen) {
      loadPersonas();
    }
  }, [isOpen]);

  const loadPersonas = async () => {
    setLoading(true);
    try {
      const data = await fetchPersonas();
      setPersonas(data);
    } catch (err) {
      console.error("Personalar yüklenemedi:", err);
      setError("Personalar yüklenirken bir hata oluştu.");
    } finally {
      setLoading(false);
    }
  };

  const handleOpenNewForm = () => {
    setEditingId(null);
    setFormData({
      name: "",
      title: "",
      description: "",
      systemPrompt: "",
      icon: "Bot",
      isDefault: false,
    });
    setError(null);
    setActiveTab("form");
  };

  const handleOpenEditForm = (persona) => {
    setEditingId(persona.id);
    setFormData({
      name: persona.name,
      title: persona.title,
      description: persona.description || "",
      systemPrompt: persona.systemPrompt,
      icon: persona.icon || "Bot",
      isDefault: persona.isDefault,
    });
    setError(null);
    setActiveTab("form");
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!formData.name.trim() || !formData.title.trim() || !formData.systemPrompt.trim()) {
      setError("Lütfen isim, unvan ve sistem promptu alanlarını doldurun.");
      return;
    }

    setSaving(true);
    setError(null);
    try {
      if (editingId) {
        await updatePersona(editingId, formData);
      } else {
        await createPersona(formData);
      }
      await loadPersonas();
      if (onPersonasUpdated) onPersonasUpdated();
      setActiveTab("list");
    } catch (err) {
      console.error("Persona kaydetme hatası:", err);
      setError("Persona kaydedilirken bir hata oluştu.");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id, name) => {
    const confirmed = window.confirm(`"${name}" adlı kişiliği silmek istediğinize emin misiniz?`);
    if (!confirmed) return;

    try {
      await deletePersona(id);
      await loadPersonas();
      if (onPersonasUpdated) onPersonasUpdated();
    } catch (err) {
      console.error("Persona silinemedi:", err);
      alert("Persona silinemedi.");
    }
  };

  if (!isOpen) return null;

  return (
    <div className="persona-modal-backdrop" onClick={onClose}>
      <div className="persona-modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="persona-modal-header">
          <div className="persona-modal-title">
            <Sparkles size={20} className="text-indigo-600" />
            <span>AI Persona & İstem Yönetimi (Admin Panel)</span>
          </div>
          <button className="icon-btn" onClick={onClose} title="Kapat">
            <X size={18} />
          </button>
        </div>

        <div className="persona-modal-tabs">
          <button
            className={`persona-tab ${activeTab === "list" ? "active" : ""}`}
            onClick={() => setActiveTab("list")}
          >
            Mevcut Personalar ({personas.length})
          </button>
          <button
            className={`persona-tab ${activeTab === "form" ? "active" : ""}`}
            onClick={handleOpenNewForm}
          >
            <Plus size={14} /> Yeni Persona Ekle
          </button>
        </div>

        <div className="persona-modal-body">
          {activeTab === "list" ? (
            loading ? (
              <div className="persona-loading">Personalar yükleniyor...</div>
            ) : (
              <div className="persona-grid-list">
                {personas.map((p) => (
                  <div key={p.id} className={`persona-admin-card ${p.isDefault ? "is-default" : ""}`}>
                    <div className="persona-admin-card-header">
                      <div>
                        <div className="persona-admin-name">
                          {p.name} {p.isDefault && <span className="default-tag">Varsayılan</span>}
                        </div>
                        <div className="persona-admin-title">{p.title}</div>
                      </div>
                      <div className="persona-admin-actions">
                        <button
                          className="icon-btn small"
                          onClick={() => handleOpenEditForm(p)}
                          title="Düzenle"
                        >
                          <Pencil size={14} />
                        </button>
                        {!p.isDefault && (
                          <button
                            className="icon-btn small danger"
                            onClick={() => handleDelete(p.id, p.name)}
                            title="Sil"
                          >
                            <Trash2 size={14} />
                          </button>
                        )}
                      </div>
                    </div>
                    <p className="persona-admin-desc">{p.description}</p>
                    <div className="persona-prompt-preview">
                      <strong>Sistem İstemi (Prompt):</strong>
                      <div className="persona-prompt-box">{p.systemPrompt}</div>
                    </div>
                  </div>
                ))}
              </div>
            )
          ) : (
            <form onSubmit={handleSubmit} className="persona-form">
              {error && (
                <div className="persona-form-error">
                  <ShieldAlert size={16} />
                  <span>{error}</span>
                </div>
              )}

              <div className="persona-form-group">
                <label>Persona İsmi (Kısa ad):</label>
                <input
                  type="text"
                  placeholder="Örn: Yazılımcı, Eleştirmen, Tur Rehberi"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  required
                />
              </div>

              <div className="persona-form-group">
                <label>Unvan / Başlık:</label>
                <input
                  type="text"
                  placeholder="Örn: Kıdemli Yazılım Mimarı & Geliştirici"
                  value={formData.title}
                  onChange={(e) => setFormData({ ...formData, title: e.target.value })}
                  required
                />
              </div>

              <div className="persona-form-group">
                <label>Açıklama (İsteğe bağlı):</label>
                <input
                  type="text"
                  placeholder="Örn: Clean code ve mimariye odaklanan teknik uzman."
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                />
              </div>

              <div className="persona-form-group">
                <label>Simge / İkon:</label>
                <select
                  value={formData.icon}
                  onChange={(e) => setFormData({ ...formData, icon: e.target.value })}
                >
                  {ICON_OPTIONS.map((opt) => (
                    <option key={opt.id} value={opt.id}>
                      {opt.label}
                    </option>
                  ))}
                </select>
              </div>

              <div className="persona-form-group">
                <label>Sistem İstemi (System Prompt):</label>
                <textarea
                  rows={5}
                  placeholder="Yapay zekanın nasıl davranacağını, hangi rolü üstleneceğini ve yanıt üslubunu detaylıca yazın..."
                  value={formData.systemPrompt}
                  onChange={(e) => setFormData({ ...formData, systemPrompt: e.target.value })}
                  required
                />
              </div>

              <div className="persona-form-checkbox">
                <label>
                  <input
                    type="checkbox"
                    checked={formData.isDefault}
                    onChange={(e) => setFormData({ ...formData, isDefault: e.target.checked })}
                  />
                  <span>Varsayılan Persona Yap (Yeni sohbetlerde otomatik seçilir)</span>
                </label>
              </div>

              <div className="persona-form-footer">
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => setActiveTab("list")}
                  disabled={saving}
                >
                  İptal
                </button>
                <button type="submit" className="btn-primary" disabled={saving}>
                  {saving ? "Kaydediliyor..." : editingId ? "Güncelle" : "Persona Oluştur"}
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
