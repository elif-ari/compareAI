# CompareAI

CompareAI, aynı kullanıcı mesajını birden fazla yapay zekâ modeline göndererek üretilen cevapları tek ekranda karşılaştırmayı sağlayan bir web uygulamasıdır.

Kullanıcı, farklı yapay zekâ modellerinin cevaplarını eş zamanlı olarak inceleyebilir ve beğendiği cevabı seçerek konuşmayı yalnızca o model üzerinden sürdürebilir. Böylece farklı modellerin aynı soruya verdiği yanıtlar kolayca karşılaştırılabilir ve konuşma istenilen model üzerinde dallandırılarak devam ettirilebilir.

---

# Özellikler

* Aynı mesajı birden fazla yapay zekâ modeline göndererek cevapları karşılaştırma
* **Compare Chat** modu ile seçilen tüm yapay zekâ modellerinden aynı anda cevap alma
* **Independent Chat** modu ile seçilen tek bir yapay zekâ modeliyle bağımsız sohbet etme
* Cevaplardan istenilen biri üzerinden konuşmaya devam etme (Conversation Branching)
* Broadcast (tüm modellere gönder) ve Single Provider (tek model ile devam et) desteği
* Kullanıcı kayıt ve giriş sistemi
* Güvenli parola saklama (BCrypt)
* Çok katmanlı Spring Boot mimarisi
* REST API
* Docker destekli geliştirme ortamı

---

# Sistem Akışı

1. Kullanıcı sisteme kayıt olur veya giriş yapar.
2. Kullanıcı **Compare Chat** veya **Independent Chat** modlarından birini seçer.
3. Compare Chat modunda karşılaştırmak istediği yapay zekâ modellerini belirler.
4. Gerekirse API anahtarlarını tanımlar.
5. Mesaj seçilen yapay zekâ model(ler)ine gönderilir.
6. Compare Chat modunda cevaplar aynı ekranda karşılaştırmalı olarak gösterilir.
7. Kullanıcı istediği cevabı seçerek yalnızca o model üzerinden konuşmaya devam edebilir veya tekrar tüm modellere soru gönderebilir.
