# CompareAI

Birden fazla yapay zeka servisinin (ChatGPT, Claude, Gemini) aynı metne verdiği
cevapları tek ekranda karşılaştırmalı olarak gösteren, en beğenilen cevap
üzerinden konuşmaya devam edilebilen bir platform.

## Proje Yapısı

```
compareAI/
├── backend/     → Spring Boot REST API (Java 17, MySQL, JPA)
├── frontend/    → React arayüzü
├── docs/        → Mimari dokümanlar, API referansı, veritabanı şeması
└── docker-compose.yml → MySQL'i tek komutla ayağa kaldırmak için
```

## Kullanılan Teknolojiler

- **Backend:** Java 17, Spring Boot 3, Spring Data JPA, MySQL
- **Frontend:** React (Axios ile backend bağlantısı)
- **AI Entegrasyonları:** OpenAI (ChatGPT), Anthropic (Claude), Google (Gemini) API'leri
- **Veritabanı:** MySQL

## Kurulum

### Backend
Ayrıntılı kurulum adımları için [backend/README.md](backend/README.md) dosyasına bakınız
(MySQL bağlantısı, `application.properties` ayarları, çalıştırma).

### Frontend
`frontend/` klasörü ileride eklenecek. Kurulum talimatları o aşamada buraya eklenecek.

### MySQL (Docker ile, opsiyonel)
```bash
docker-compose up -d
```

## Mimari

Detaylı mimari açıklaması için [docs/architecture.md](docs/architecture.md) dosyasına bakınız.

## Proje Durumu

- [x] Backend iskeleti (Spring Boot + MySQL bağlantısı)
- [ ] Entity / Repository / JPA yapısı (Conversation, Message)
- [ ] REST API'ler
- [ ] React arayüzü
- [ ] AI API entegrasyonları (OpenAI, Claude, Gemini)
- [ ] Paralel API çağrıları
- [ ] Responsive tasarım ve hata yönetimi
