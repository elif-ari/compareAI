# CompareAI

CompareAI, aynı kullanıcı mesajını birden fazla yapay zeka modeline (ChatGPT, Claude ve Gemini) göndererek üretilen cevapları tek ekranda karşılaştırmalı olarak gösteren bir platformdur. Kullanıcı, beğendiği yapay zeka cevabını seçerek konuşmaya o model üzerinden devam edebilir.

---

# Proje Yapısı

```
compareAI/
├── backend/              # Spring Boot REST API
├── frontend/             # React uygulaması (geliştirme aşamasında)
├── docs/                 # Mimari dokümanlar
└── docker-compose.yml    # MySQL Docker yapılandırması
```

---

# Kullanılan Teknolojiler

## Backend
- Java 17
- Spring Boot 3
- Spring Web
- Spring Data JPA
- MySQL
- Maven

## Frontend
- React
- Axios

## AI Entegrasyonları
- OpenAI (ChatGPT)
- Anthropic Claude
- Google Gemini

## Diğer
- Docker
- Git
- Postman

---

# Mevcut Özellikler

- Çok katmanlı (Layered Architecture) backend yapısı
- Conversation ve Message veri modeli
- JPA Repository katmanı
- Mock AI servisleri
- Tek endpoint üzerinden çoklu AI isteği
- REST API mimarisi
- Postman ile test edilebilir API

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

## 2. MySQL (Docker)

```bash
docker-compose up -d
```

---

# API

## Mesaj Gönder

```
POST /api/chat
```

Body (Text):

```
Merhaba
```

Örnek Response

```json
[
  {
    "provider": "OPENAI",
    "response": "[ChatGPT MOCK CEVABI] Sorduğun soru: \"Merhaba\"",
    "success": true,
    "error": null
  },
  {
    "provider": "CLAUDE",
    "response": "[CLAUDE MOCK CEVABI] Sorduğun soru: \"Merhaba\"",
    "success": true,
    "error": null
  },
  {
    "provider": "GEMINI",
    "response": "[GEMINI MOCK CEVABI] Sorduğun soru: \"Merhaba\"",
    "success": true,
    "error": null
  }
]
```

---

# Frontend Kullanıcı Akışı (v1)

Frontend artık React Router ile 4 adımlı bir akış üzerinden çalışıyor:

1. **`/login`** — Kayıt / Giriş. Backend'de auth servisi olmadığı için bu v1'de
   tarayıcı `localStorage`'ında tutulan basit bir demo hesap sistemidir (üretimde
   gerçek bir backend auth servisine bağlanmalı).
2. **`/select`** — Karşılaştırılacak yapay zekaları grupları içinde seç
   (Büyük ticari modeller, Açık kaynak modeller, Kod odaklı modeller, Vision
   modelleri). En az **2**, en fazla **10** model seçilebilir.
3. **`/keys`** — Seçilen her model için API anahtarını yapıştır. Anahtarlar
   yalnızca tarayıcıda saklanır.
4. **`/compare`** — Seçilen sayıda (2-10) konteynerin gösterildiği karşılaştırma
   ekranı.

**Önemli:** v1'de backend'e hiçbir değişiklik yapılmadı — hâlâ yalnızca 3 mock
client var (`OPENAI`, `CLAUDE`, `GEMINI`). Bu yüzden seçim ekranında **ChatGPT,
Claude ve Gemini "Canlı"** etiketiyle işaretli ve gerçekten backend'den cevap
alıyor; listedeki diğer tüm modeller (Grok, Llama, Mistral, DeepSeek vb.) v1'de
yalnızca arayüzün nasıl büyüyeceğini göstermek amacıyla seçilebilir durumda,
ama gerçek bir API çağrısı yapmıyorlar ("v2'de aktif olacak" etiketi).

## Bu oturumda düzeltilen buglar

- **Konuşma sürekliliği kırıktı:** `handleSendMessage` backend'e `conversationId`
  göndermiyordu, bu yüzden her mesaj yeni bir konuşma açıyordu. Artık aktif
  konuşma id'si state'te tutuluyor ve her istekle birlikte gönderiliyor.
- **Geçmiş sohbetler tıklanamıyordu:** Sol menüdeki `history-item` butonlarında
  `onClick` yoktu. Artık bir sohbete tıklayınca `GET /conversations/{id}` ile
  tüm mesajlar çekilip ilgili dal (branch) ekrana yansıtılıyor.
- **"X ile devam et" arayüze yansımıyordu:** Dal seçimi backend'de kaydediliyordu
  ama ekranda hiçbir şey değişmiyordu. Artık seçilen kart görsel olarak
  vurgulanıyor ve konuşmanın HEAD'i güncelleniyor.
- **Sidebar'da görsel bug:** `index.css` içinde `border-right: 1px border #e2e8f0;`
  geçersiz bir CSS değeriydi (`solid` yerine `border` yazılmıştı), bu yüzden
  sidebar'ın sağ kenarlığı hiç görünmüyordu.

---

# Mimari

Detaylı mimari açıklamaları ve diyagramlar `docs/architecture.md` dosyasında yer almaktadır.

---

# Geliştirme Yol Haritası

## Tamamlananlar

- [x] Spring Boot backend kurulumu
- [x] MySQL bağlantısı
- [x] Docker Compose yapılandırması
- [x] Conversation ve Message entity'leri
- [x] Repository katmanı
- [x] Mock OpenAI servisi
- [x] Mock Claude servisi
- [x] Mock Gemini servisi
- [x] ChatService
- [x] ChatController
- [x] Mock AI entegrasyon testi (Postman)

## Devam Eden

- [ ] CompletableFuture ile paralel AI çağrıları
- [ ] Gerçek OpenAI API entegrasyonu
- [ ] Gerçek Claude API entegrasyonu
- [ ] Gerçek Gemini API entegrasyonu
- [ ] React kullanıcı arayüzü
- [ ] Konuşma geçmişi
- [ ] Branch (beğenilen cevaptan devam etme)
- [ ] Responsive tasarım
- [ ] Hata yönetimi
