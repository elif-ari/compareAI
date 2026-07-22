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
