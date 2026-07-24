// Karşılaştırılabilir yapay zeka kataloğu.
// v1'de yalnızca backend tarafında gerçekten desteklenen 3 sağlayıcı gösterilir
// (bkz. backend AiProvider enum: OPENAI, CLAUDE, GEMINI). API anahtarları artık
// backend üzerinden yönetildiği için burada anahtar/işlevsellik bayrağına gerek yok.

export const REAL_PROVIDERS = [
  {
    id: "openai",
    vendor: "OpenAI",
    name: "ChatGPT",
    detail: "GPT-4.1 / GPT-4o mini",
    backendProvider: "OPENAI",
  },
  {
    id: "anthropic",
    vendor: "Anthropic",
    name: "Claude",
    detail: "Sonnet 4.6",
    backendProvider: "CLAUDE",
  },
  {
    id: "google",
    vendor: "Google",
    name: "Gemini",
    detail: "2.0 Flash",
    backendProvider: "GEMINI",
  },
];

export const getProviderById = (id) => REAL_PROVIDERS.find((p) => p.id === id);
export const getProviderByBackendName = (backendProvider) =>
  REAL_PROVIDERS.find((p) => p.backendProvider === backendProvider);

export const MIN_SELECTION = 1;
export const MAX_SELECTION = REAL_PROVIDERS.length;

// Sohbet modları
export const CHAT_MODES = {
  INDEPENDENT: "INDEPENDENT",
  COMPARE: "COMPARE",
};

// Kart renk paleti (avatar / kenarlık) - index'e göre döngüsel atanır.
export const CARD_PALETTE = [
  { bg: "#3b82f6", border: "#bfdbfe", text: "#2563eb", tint: "#eff6ff" },
  { bg: "#f97316", border: "#fed7aa", text: "#ea580c", tint: "#fff7ed" },
  { bg: "#10b981", border: "#bbf7d0", text: "#059669", tint: "#ecfdf5" },
];
