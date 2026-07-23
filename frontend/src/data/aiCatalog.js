// Karşılaştırılabilir yapay zeka kataloğu.
// `functional: true` olanlar backend'deki gerçek (şimdilik mock) client'lara bağlıdır
// (bkz. backend/.../client/Mock*Client.java -> AiProvider enum: OPENAI, CLAUDE, GEMINI).
// Diğerleri v1'de sadece görsel/UX amaçlıdır; seçilebilir ve API key alanı gösterilir
// ama gerçek bir çağrı yapılmaz.

export const AI_GROUPS = [
  {
    id: "ticari",
    title: "Büyük ticari modeller",
    providers: [
      {
        id: "openai",
        vendor: "OpenAI",
        name: "ChatGPT",
        detail: "GPT-4.1 / GPT-4o / GPT-5 serisi",
        functional: true,
        backendProvider: "OPENAI",
      },
      {
        id: "anthropic",
        vendor: "Anthropic",
        name: "Claude",
        detail: "Opus / Sonnet / Haiku",
        functional: true,
        backendProvider: "CLAUDE",
      },
      {
        id: "google",
        vendor: "Google",
        name: "Gemini",
        detail: "2.5 Pro / Flash",
        functional: true,
        backendProvider: "GEMINI",
      },
      { id: "xai", vendor: "xAI", name: "Grok", detail: "Grok serisi" },
      { id: "cohere", vendor: "Cohere", name: "Command R / R+", detail: "Cohere" },
      { id: "ai21", vendor: "AI21 Labs", name: "Jamba / Jurassic", detail: "AI21 Labs" },
      { id: "amazon", vendor: "Amazon", name: "Nova", detail: "Amazon Nova" },
      { id: "microsoft", vendor: "Microsoft", name: "Phi serisi", detail: "Microsoft" },
    ],
  },
  {
    id: "acik-kaynak",
    title: "Açık kaynak / açık ağırlıklı modeller",
    providers: [
      { id: "meta", vendor: "Meta", name: "Llama 3.x", detail: "Meta" },
      { id: "mistral", vendor: "Mistral AI", name: "Mistral / Mixtral", detail: "Mistral AI" },
      { id: "deepseek", vendor: "DeepSeek", name: "DeepSeek Chat / R1", detail: "DeepSeek" },
      { id: "alibaba", vendor: "Alibaba", name: "Qwen 2.5 / Qwen 3", detail: "Alibaba" },
      { id: "google-gemma", vendor: "Google", name: "Gemma", detail: "Google" },
      { id: "microsoft-phi4", vendor: "Microsoft", name: "Phi-4", detail: "Microsoft" },
      { id: "nvidia", vendor: "NVIDIA", name: "Nemotron", detail: "NVIDIA" },
      { id: "ibm", vendor: "IBM", name: "Granite", detail: "IBM" },
      { id: "01ai", vendor: "01.AI", name: "Yi", detail: "01.AI" },
      { id: "zhipu", vendor: "Zhipu AI", name: "GLM", detail: "Zhipu AI" },
      { id: "nous", vendor: "Nous Research", name: "Hermes", detail: "Nous Research" },
      { id: "allenai", vendor: "Allen AI", name: "OLMo", detail: "Allen AI" },
      { id: "tii", vendor: "TII (Abu Dhabi)", name: "Falcon", detail: "TII" },
    ],
  },
  {
    id: "kod",
    title: "Kod yazmaya odaklı modeller",
    providers: [
      { id: "deepseek-coder", vendor: "DeepSeek", name: "DeepSeek Coder", detail: "DeepSeek" },
      { id: "qwen-coder", vendor: "Alibaba", name: "Qwen Coder", detail: "Alibaba" },
      { id: "codellama", vendor: "Meta", name: "Code Llama", detail: "Meta" },
      { id: "starcoder2", vendor: "BigCode", name: "StarCoder2", detail: "BigCode" },
      { id: "codestral", vendor: "Mistral AI", name: "Codestral", detail: "Mistral AI" },
      { id: "devstral", vendor: "Mistral AI", name: "Devstral", detail: "Mistral AI" },
      { id: "replit", vendor: "Replit", name: "Replit Code V1", detail: "Replit" },
    ],
  },
  {
    id: "vision",
    title: "Görsel destekli modeller (Vision)",
    providers: [
      { id: "gpt-vision", vendor: "OpenAI", name: "GPT-4o / GPT-5", detail: "Vision" },
      { id: "gemini-vision", vendor: "Google", name: "Gemini Vision", detail: "Vision" },
      { id: "claude-vision", vendor: "Anthropic", name: "Claude Vision", detail: "Vision" },
      { id: "qwen-vl", vendor: "Alibaba", name: "Qwen VL", detail: "Vision" },
      { id: "llama-vision", vendor: "Meta", name: "Llama Vision", detail: "Vision" },
      { id: "pixtral", vendor: "Mistral AI", name: "Pixtral", detail: "Vision" },
    ],
  },
];

// Tüm sağlayıcıları düz bir listede almak için yardımcı.
export const ALL_PROVIDERS = AI_GROUPS.flatMap((g) =>
  g.providers.map((p) => ({ ...p, group: g.title }))
);

export const getProviderById = (id) => ALL_PROVIDERS.find((p) => p.id === id);

export const MIN_SELECTION = 2;
export const MAX_SELECTION = 10;

// Kart renk paleti (avatar / kenarlık) - index'e göre döngüsel atanır.
export const CARD_PALETTE = [
  { bg: "#3b82f6", border: "#bfdbfe", text: "#2563eb", tint: "#eff6ff" },
  { bg: "#f97316", border: "#fed7aa", text: "#ea580c", tint: "#fff7ed" },
  { bg: "#10b981", border: "#bbf7d0", text: "#059669", tint: "#ecfdf5" },
  { bg: "#a855f7", border: "#e9d5ff", text: "#9333ea", tint: "#faf5ff" },
  { bg: "#ec4899", border: "#fbcfe8", text: "#db2777", tint: "#fdf2f8" },
  { bg: "#14b8a6", border: "#99f6e4", text: "#0d9488", tint: "#f0fdfa" },
  { bg: "#f59e0b", border: "#fde68a", text: "#d97706", tint: "#fffbeb" },
  { bg: "#6366f1", border: "#c7d2fe", text: "#4f46e5", tint: "#eef2ff" },
  { bg: "#ef4444", border: "#fecaca", text: "#dc2626", tint: "#fef2f2" },
  { bg: "#0ea5e9", border: "#bae6fd", text: "#0284c7", tint: "#f0f9ff" },
];
