import axios from "axios";

// ChatController ile konuşan tüm istekler burada toplanır, böylece Dashboard ve Chat
// sayfaları aynı uç noktaları tekrar tekrar yazmak zorunda kalmaz.

const API_BASE = "http://localhost:8080/api/chat";

// Dashboard: bir kullanıcıya ait tüm konuşmaları (özet halinde) getirir.
export const fetchConversations = async (userId) => {
  const res = await axios.get(`${API_BASE}/conversations`, { params: { userId } });
  return res.data;
};

// Chat: kaydedilmiş bir konuşma açıldığında tüm mesajlarıyla birlikte getirir.
export const fetchConversation = async (conversationId) => {
  const res = await axios.get(`${API_BASE}/conversations/${conversationId}`);
  return res.data;
};

// Dashboard: bir konuşmayı sabitler veya sabitlemesini kaldırır.
export const togglePinConversation = async (conversationId) => {
  const res = await axios.post(`${API_BASE}/conversations/${conversationId}/pin`);
  return res.data;
};

// Dashboard: bir konuşmanın başlığını düzenler.
export const renameConversation = async (conversationId, title) => {
  const res = await axios.patch(`${API_BASE}/conversations/${conversationId}`, { title });
  return res.data;
};

// Dashboard: bir konuşmayı (tüm mesajlarıyla birlikte) siler.
export const deleteConversation = async (conversationId) => {
  await axios.delete(`${API_BASE}/conversations/${conversationId}`);
};

export default API_BASE;
