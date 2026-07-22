# Mimari

## Genel Akış

```
[React Frontend]
      │  (kullanıcı tek metin girer)
      ▼
[Spring Boot Backend - Controller]
      │
      ▼
[Service Katmanı] ──► CompletableFuture ile paralel çağrı
      │
      ├──► [OpenAI Client]  ──► ChatGPT API
      ├──► [Claude Client]  ──► Anthropic API
      └──► [Gemini Client]  ──► Google Gemini API
      │
      ▼
[3 cevap birleştirilir, tek response olarak döner]
      │
      ▼
[React - 3 panelde gösterilir]
      │
      ▼ (kullanıcı bir cevabı beğenip seçerse)
[MySQL - Conversation & Message tabloları güncellenir]
```

## Veritabanı Şeması (taslak)

**Conversation**
| Alan | Tip | Açıklama |
|---|---|---|
| id | Long | Primary Key |
| title | String | Konuşma başlığı |
| createdAt | LocalDateTime | Oluşturulma tarihi |

**Message**
| Alan | Tip | Açıklama |
|---|---|---|
| id | Long | Primary Key |
| conversationId | Long | Foreign Key → Conversation |
| role | Enum (USER, ASSISTANT) | Mesajı kim gönderdi |
| aiProvider | Enum (OPENAI, CLAUDE, GEMINI, null) | Hangi AI'den geldi (kullanıcı mesajında null) |
| content | Text | Mesaj içeriği |
| isSelected | Boolean | Kullanıcının "devam et" için seçtiği cevap mı |
| createdAt | LocalDateTime | Oluşturulma tarihi |

> Not: Bu şema geliştirme ilerledikçe güncellenecektir. `database.png` görseli
> Entity/Repository katmanı tamamlandığında buraya eklenecek.

## Paralel API Çağrı Stratejisi

Backend, kullanıcıdan gelen tek metni 3 AI servisine aynı anda gönderir
(`CompletableFuture.allOf`). Bir servis hata verirse veya zaman aşımına
uğrarsa, diğer ikisinin cevabı yine de kullanıcıya ulaşır — hiçbiri
diğerini bloklamaz.
