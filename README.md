# CompareAI

CompareAI, aynı kullanıcı mesajını birden fazla yapay zeka modeline (ChatGPT, Claude ve Gemini) göndererek üretilen cevapları tek ekranda karşılaştırmalı olarak gösteren bir platformdur. Kullanıcı, beğendiği yapay zeka cevabını seçerek konuşmaya o model üzerinden devam edebilir veya modellerin kendi aralarında tartışmasını sağlayabilir.

---

# Proje Yapısı

```
compareAI/
├── backend/              # Spring Boot REST API
├── frontend/             # React uygulaması (Vite + React Router)
├── docs/                 # Mimari dokümanlar
└── docker-compose.yml    # MySQL Docker yapılandırması
```

---

# Kullanılan Teknolojiler

## Backend
- Java 17
- Spring Boot 3
- Spring Web & SSE (Server-Sent Events)
- Spring Data JPA
- BCrypt Password Encoder
- MySQL
- Maven

## Frontend
- React (Vite)
- React Router DOM
- Axios & Fetch API (ReadableStream SSE)
- React Markdown & Remark GFM
- Lucide React (İkon Seti)

## AI Entegrasyonları
- OpenAI (ChatGPT)
- Anthropic Claude
- Google Gemini

## Diğer
- Docker
- Git

---

# Mevcut Özellikler

- **Çok Katmanlı Backend Mimarisi:** Controller, Service, Repository ve Entity katmanları.
- **Canlı Yanıt Akışı (SSE Streaming):** Yanıtlar tek parça beklenmeden sunucudan anında akar (`POST /api/chat/stream` ve `POST /api/chat/debate/stream`).
- **Canlı Akışı Durdurma (Stop Stream):** İstek akarken kırmızı `[■ Durdur]` butonu ile `AbortController` kullanılarak istek iptal edilebilir.
- **Zengin Markdown & Kod Kopyalama:** `react-markdown` ve `remark-gfm` entegrasyonu ile kod blokları, tablolar ve listeler biçimlendirilmiş gösterilir. Kod bloklarında 1-tıkla "Kopyala" ikonu mevculttur.
- **Otomatik Tartışma Modu (Auto Debate):** Seçilen modellerin belirlenen tur sayısı kadar (2-6 tur) birbirlerinin fikirlerini savunmasını/eleştirmesini ve en son bir moderatör modelin nihai sentez üretmesini sağlar.
- **Dinamik Tur Sayısı Seçici:** Tartışma modu için `[ 2 Tur ]` - `[ 6 Tur ]` arası dinamik açılır menü (dropdown).
- **Cevap Kopyalama & Tekrar Üret (Regenerate):** Her AI yanıt balonunda tek tıkla cevabı kopyalama ve soruyu sadece o modele yeniden sordurma imkanı.
- **Tam Ekran Kart Odağı (Maximize Focus View):** Kart sağ üstündeki büyütme ikonuyla (ESC ile kapanan) 88vh boyutunda odaklanmış okuma ve sohbet modali.
- **Yumuşak Pastel Tercih Renkleri & Tercih Kaldırma (Toggle):** `✓ Tercih edildi` olarak işaretlenen cevaplarda modellerin marka renklerine özel (Mavi, Turuncu, Yeşil) yumuşak pastel arka planlar kullanılır. Tercih butona tekrar tıklandığında anında kaldırılabilir (toggle).
- **Bağımsız Sohbet Modu (Independent Chat):** Modellerin birbirini görmediği, tartışma alıntıları içermeyen sade multi-bot sohbet modu.
- **Gerçek Veritabanı Tabanlı Auth:** `POST /api/auth/register` ve `POST /api/auth/login` endpoint'leri üzerinden BCrypt şifreli kullanıcı kaydı ve girişi.

---

# Kurulum

## 1. Backend

```bash
cd backend
```

MySQL bağlantı bilgilerini `application.properties` dosyasında düzenleyin.

Projeyi çalıştırın:

```bash
./mvnw spring-boot:run
```

veya IntelliJ IDEA üzerinden `CompareAiApplication` sınıfını çalıştırın.

---

## 2. Frontend

```bash
cd frontend
npm install
npm run dev
```

---

## 3. MySQL (Docker)

```bash
docker-compose up -d
```

---

# API Endpoint'leri

## 1. Canlı Chat Akışı (SSE)
```
POST /api/chat/stream
```

## 2. Otomatik Tartışma Akışı (SSE)
```
POST /api/chat/debate/stream
```

## 3. Cevap Tercih Etme / Kaldırma (Toggle)
```
POST /api/chat/conversations/{conversationId}/prefer
```

## 4. Kimlik Doğrulama
```
POST /api/auth/register
POST /api/auth/login
```

---

# Geliştirme Yol Haritası

## Tamamlananlar

- [x] Spring Boot backend ve MySQL kurulumu
- [x] Docker Compose yapılandırması
- [x] Conversation, Message ve AppUser entity'leri
- [x] SSE (Server-Sent Events) canlı yanıt akışı
- [x] AbortController ile canlı isteği durdurma (Stop Stream)
- [x] React Markdown & Remark GFM ile zengin metin ve kod bloğu kopyalama
- [x] Otomatik Tartışma Modu (2-6 tur arası açılır menü seçici ve nihai sentez)
- [x] Cevabı Kopyala ve Tekrar Üret (Regenerate) butonları
- [x] Tam ekran kart odaklama (Maximize focus view modal)
- [x] Yumuşak pastel tercih temaları ve tercihi kaldırma (toggle)
- [x] Bağımsız Sohbet (Independent Chat) ve Karşılaştırmalı Sohbet (Compare Chat) modları
- [x] BCrypt şifreli veritabanı tabanlı Giriş / Kayıt (Auth) sistemi

## Gelecek Planlar (v2)

- [ ] Canlı OpenAI, Claude ve Gemini API anahtar entegrasyonu
- [ ] Diğer açık kaynak modellerin (Llama, DeepSeek vb.) canlı servise bağlanması
- [ ] Sohbet geçmişini dışa aktarma (PDF / Markdown export)
