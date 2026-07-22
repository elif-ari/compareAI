# API Referansı

> Bu doküman geliştirme ilerledikçe güncellenecektir.

## Health Check

**GET** `/api/health`

Backend'in ve bağlantıların çalışıp çalışmadığını kontrol eder.

**Örnek Cevap:**
```json
{
  "status": "OK",
  "message": "Backend ayakta ve MySQL'e baglanmaya hazir"
}
```

---

## Planlanan Endpoint'ler (ilerleyen adımlarda eklenecek)

| Method | Endpoint | Açıklama |
|---|---|---|
| POST | `/api/conversations` | Yeni konuşma oluştur |
| GET | `/api/conversations/{id}` | Konuşma geçmişini getir |
| GET | `/api/conversations` | Tüm konuşmaları listele |
| POST | `/api/conversations/{id}/messages` | Metni 3 AI'ye gönder, cevapları al |
| POST | `/api/messages/{id}/select` | Bir AI cevabını "devam et" olarak seç |
