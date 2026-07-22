# Backend Kurulumu

## 1) Projeyi IntelliJ'e Aç
- IntelliJ IDEA → File → Open → `compareAI/backend` klasörünü seç
- Maven bağımlılıklarının inmesini bekle

## 2) MySQL'i Hazırla

**Seçenek A - Kendi bilgisayarında MySQL kuruluysa:**
```sql
CREATE DATABASE compareai_db;
```

**Seçenek B - Docker kullanmak istersen:**
Proje kökünde (`compareAI/`) şunu çalıştır:
```bash
docker-compose up -d
```

## 3) application.properties Dosyasını Düzenle
`src/main/resources/application.properties` içindeki:
```
spring.datasource.password=SIFRENI_BURAYA_YAZ
```
satırını kendi MySQL şifrenle değiştir.

## 4) Projeyi Çalıştır
`MultiaichatApplication.java` → yeşil "Run" butonu.

Konsolda:
```
Multi AI Chat Backend calisiyor -> http://localhost:8080
```

## 5) Test Et
Tarayıcıdan: `http://localhost:8080/api/health`

```json
{"status":"OK","message":"Backend ayakta ve MySQL'e baglanmaya hazir"}
```

## Klasör Yapısı (paket açıklamaları)

| Klasör | Görevi |
|---|---|
| `controller` | REST API endpoint'leri (HTTP istek/cevap) |
| `service` | İş mantığı (business logic) |
| `repository` | Veritabanı erişimi (JPA repository'ler) |
| `entity` | Veritabanı tablolarına karşılık gelen sınıflar |
| `dto` | API'ler arası veri taşıma nesneleri (Data Transfer Object) |
| `config` | Yapılandırma sınıfları (CORS, güvenlik vb.) |
| `client` | Dış AI API'lerine (OpenAI, Claude, Gemini) istek atan sınıflar |
| `exception` | Özel hata sınıfları ve global hata yönetimi |
| `util` | Yardımcı/genel amaçlı sınıflar |
