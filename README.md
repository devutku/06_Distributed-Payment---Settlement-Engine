# 💳 Mutabık | Enterprise Distributed Payment & Settlement Engine
### Multi-Module Clean Architecture &bull; Double-Entry Bookkeeping &bull; Transactional Outbox &bull; Idempotent API &bull; Deadlock-Free Concurrency

[![Java 21](https://img.shields.io/badge/Java-21_LTS-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.5-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)](https://spring.io/)
[![Clean Architecture](https://img.shields.io/badge/Architecture-Clean%20%2F%20Hexagonal-blue?style=for-the-badge)](https://blog.cleancoder.com/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16_Pessimistic_Lock-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-KRaft_DLQ-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-7_Idempotency_Lock-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Prometheus](https://img.shields.io/badge/Prometheus-Metrics_p95_p99-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)](https://prometheus.io/)
[![Grafana](https://img.shields.io/badge/Grafana-Live_Dashboard-F46800?style=for-the-badge&logo=grafana&logoColor=white)](https://grafana.com/)
[![Docker](https://img.shields.io/badge/Docker_Compose-Ready-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)

## 📌 Proje Genel Bakışı & Vitrin Değeri

**Mutabık**, modern bankacılık, ödeme geçidi (Payment Gateway) ve fintech sistemlerinin (Adyen, Stripe, Wise, BKM vb.) yüksek hacimli ve sıfır hata toleranslı para transferi gereksinimlerine göre inşa edilmiş, **uçtan uca üretime hazır (production-grade)** bir dağıtık mutabakat ve transfer motorudur.

Basit CRUD veya tek veritabanlı basit transfer örneklerinin ötesine geçerek; **Clean Architecture (Hexagonal / Ports & Adapters)** prensipleriyle tasarlanmış, **PostgreSQL Row-Level Pessimistic Locking** ile yarış koşullarını (Race Conditions) ve **Deterministik Kilit Sıralaması** ile kilitlenme döngülerini (Deadlocks) matematiksel olarak imkansız kılan bir mimari sunar.

Ağ kesintilerinde mükerrer para çekimini engelleyen **Redis tabanlı Idempotent API katmanı**, dağıtık veri kaybını bitiren **Transactional Outbox Pattern**, zehirli mesajları (Poison Pills) ana akışı tıkamadan izole eden **Kafka Exponential Backoff & Dead Letter Queue (DLQ)** ve bankacılık standardı **Çift Taraflı Muhasebe (Double-Entry Bookkeeping Ledger)** tek bir potada birleştirilmiştir.

---

## 🏛️ Çoklu Modül Mimarisi (Multi-Module Clean Architecture)

Proje, Robert C. Martin'in (Uncle Bob) **Dependency Rule** (Bağımlılık Kuralı) esasına göre 4 bağımsız modüle ayrılmıştır. İç katmanlar hiçbir şekilde dış katmanları bilmez; dış katmanlar iç katmanlara bağımlıdır:

```
[payment-api] ───► [payment-application] ───► [payment-domain]
       │                       ▲
       └──► [payment-infrastructure] ──┘ (Port Implementasyonları - DIP)
```

```
distributed-payment-core/
├── payment-domain/          # Sıfır harici kütüphane bağımlılığı (Saf Java 21, Record'lar, DDD Aggregate Root)
├── payment-application/     # Use Case'ler, Inbound / Outbound Portlar, CQRS komutları, Orkestratör
├── payment-infrastructure/  # Spring Data JPA, Redis, Kafka, Outbox ve Ledger adaptörleri
├── payment-api/             # Spring Boot bootstrap, REST Controller'lar, IdempotencyFilter, OAuth2 Resource Server, UI Konsolu
└── docker/                  # PostgreSQL 16, Redis 7, Kafka KRaft, Keycloak (realm), Prometheus ve Grafana konfigürasyonları
```

### Modül Sorumlulukları

| Modül | Bağımlılıklar | Açıklama |
| :--- | :--- | :--- |
| **`payment-domain`** | **Sıfır kütüphane (Saf Java 21)** | `Account`, `Transfer`, `Money` (Banker's rounding), `Currency`, `AccountId`, `IdempotencyKey` ve Domain Event'leri barındırır. |
| **`payment-application`** | `payment-domain` | `InitiateTransferUseCase`, `GetAccountBalanceQuery`, Outbound Port'lar (`LoadAccountPort`, `SaveTransferPort`, `IdempotencyPort`, `OutboxEventPort`) ve UseCase orkestrasyonu. |
| **`payment-infrastructure`** | `payment-application`, `payment-domain` | Spring Data JPA entity ve repository'leri, Redis adaptörü, Kafka Producer/Consumer ve Outbox kuyruğu. |
| **`payment-api`** | Tüm alt modüller | HTTP REST uçları, `IdempotencyFilter`, RFC 7807 `ProblemDetails` exception advice, Micrometer metrikleri ve Canlı Yönetim Konsolu (`index.html`). |

---

## ⚙️ Çekirdek Domain Modelleri

### 1. `Money.java` (Finansal Değer Nesnesi - Value Object)
Finansal işlemlerde `double` ya da `float` veri tipleri yuvarlama hassasiyeti kaybı (IEEE 754 Floating-Point Error) nedeniyle **kesinlikle yasaktır**. `BigDecimal` ve kur kontrolü zorunludur:

```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "Tutar null olamaz");
        Objects.requireNonNull(currency, "Para birimi null olamaz");
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidTransferAmountException("Bakiye veya tutar negatif olamaz");
        }
        amount = amount.setScale(2, RoundingMode.HALF_EVEN); // Banker's Rounding standardı
    }

    public Money subtract(Money other) {
        validateCurrency(other);
        if (this.isLessThan(other)) {
            throw new InvalidTransferAmountException("Yetersiz bakiye");
        }
        return new Money(this.amount.subtract(other.amount), this.currency);
    }
}
```

### 2. `Account.java` (Aggregate Root)
Hesap bakiyesinin kontrolü ve borçlandırma/alacaklandırma iş mantığı dış servislerde değil, doğrudan entity'nin kendi sınırları içindedir:

```java
public class Account {
    private final AccountId id;
    private final String customerNumber;
    private Money balance;
    private AccountStatus status;
    private final Long version; // Optimistic locking sürümü

    public void debit(Money amount) {
        validateActive();
        if (this.balance.isLessThan(amount)) {
            throw new InsufficientFundsException("Hesapta yeterli bakiye bulunmuyor. Mevcut: " + balance.amount());
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(Money amount) {
        validateActive();
        this.balance = this.balance.add(amount);
    }
}
```

---

## 🛡️ Kritik Mimari Bileşenler & Mülakat Kazanım Noktaları

### 1. Deterministik Kilit Sırası ile Deadlock Çözümü (Deterministic Locking Order)
**Mülakat Sorusu:** *"Aynı anda Alice Bob'a 100 TL gönderirken, Bob da Alice'e 50 TL gönderirse ne olur?"*

İki farklı thread hesapları ters sırada kilitlemeye çalışırsa klasik **Deadlock (Ölümcül Kilitlenme)** oluşur:
- **Thread 1:** Alice'i kilitledi, Bob'u bekliyor.
- **Thread 2:** Bob'u kilitledi, Alice'i bekliyor.
- **Sonuç:** PostgreSQL Deadlock Detector devreye girer ve işlemlerden birini `PSQLException: deadlock detected` ile öldürür.

**Mutabık Çözümü:**
Hesapların ID değerleri sıralanır (`min(id1, id2)` ve `max(id1, id2)`). Hangi thread gelirse gelsin, her zaman önce küçük ID'li hesaba `SELECT ... FOR UPDATE` (Pessimistic Lock) atar. Dairesel bekleme (Coffman Circular Wait) matematiksel olarak imkansız hale gelir:

```java
// TransferExecutionService.java
AccountId sourceId = command.sourceAccountId();
AccountId targetId = command.targetAccountId();

// Kilit Sıralaması (Deterministic Lock Ordering)
AccountId firstId = sourceId.compareTo(targetId) < 0 ? sourceId : targetId;
AccountId secondId = sourceId.compareTo(targetId) < 0 ? targetId : sourceId;

Account firstLocked = loadAccountPort.loadAccountWithLock(firstId)
        .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı: " + firstId));
Account secondLocked = loadAccountPort.loadAccountWithLock(secondId)
        .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı: " + secondId));
```

### 2. İki Kademeli Idempotent API Katmanı (Two-Tier Idempotency)
Ağ gecikmesi, istemci zaman aşımı veya kullanıcının "Gönder" butonuna çift tıklaması durumunda aynı isteğin iki kez işlenip hesabın iki kez borçlandırılması engellenir:

```
[İSTEMCİ] ──(Idempotency-Key: tx-101)──► [IdempotencyFilter]
                                                 │
                   ┌─────────────────────────────┴─────────────────────────────┐
                   ▼                                                           ▼
       [1. Redis Fast-Path]                                        [2. In-Flight SETNX Lock]
 ├── Önbellekte Var mı?                                      ├── Anahtar kilitlendi mi?
 │    ├── EVET: Eski yanıtı dön (0 ms, 0 DB yükü)            │    ├── EVET: İşlem devam ediyor (409 Conflict)
 │    └── HAYIR: Kilidi al ve işleme başla                   │    └── HAYIR: 30s TTL ile kilidi al
```

### 3. Dual-Write Problemi ve Transactional Outbox Pattern
**Mülakat Sorusu:** *"Para transferi veritabanına yazıldıktan hemen sonra Kafka çökerse ne olur? Veya Kafka'ya yazıldıktan sonra DB rollback olursa?"*

Buna mikroservis mimarilerinde **Dual-Write Problemi** denir. Mutabık bu sorunu **Transactional Outbox Pattern** ile çözer:
1. Para transferi, bakiye güncellemeleri ve `OutboxJpaEntity` **aynı tek PostgreSQL veritabanı işleminde (`@Transactional`)** diske yazılır (ACID güvencesi).
2. Asenkron `KafkaTransferEventPublisher` arka planda pending kayıtları okuyup Apache Kafka'nın `transfer-events` topic'ine fırlatır.
3. Event başarıyla broker'a iletildikten sonra status `PUBLISHED` olarak güncellenir. Veri kaybı sıfırdır.

```
┌────────────────────────────────────────────────────────┐
│             ATOMIC POSTGRESQL TRANSACTION              │
│                                                        │
│  1. Deduct Alice Balance  (accounts tablosu)           │
│  2. Credit Bob Balance    (accounts tablosu)           │
│  3. Save Transfer Record  (transfers tablosu)          │
│  4. Save Double Entry     (ledger_entries tablosu)     │
│  5. Save Outbox Event     (outbox_events tablosu)      │
└───────────────────────────┬────────────────────────────┘
                            │ (DB Commit Başarılı)
                            ▼
              [Scheduled Outbox Publisher]
                            │
                            ▼
                  [Apache Kafka Cluster]
               Topic: `transfer-events`
```

### 4. Non-Blocking Retry ve Dead Letter Queue (DLQ)
Tüketici servislerde (ör. analitik, fraud, bildirim motoru) geçici bir hata veya zehirli mesaj (Poison Pill) oluştuğunda;
- Standart döngü tüketiciyi kilitler (Head-of-Line Blocking).
- Mutabık, Spring Kafka `DefaultErrorHandler` ve `DeadLetterPublishingRecoverer` kullanarak üstel geri çekilme (`ExponentialBackOff`: 1s, 2s, 4s...) uygular.
- Maksimum deneme hakkı bittiğinde mesaj otomatik olarak `transfer-events.DLT` kuyruğuna aktarılır ve operasyonel alarmlar tetiklenir.

### 5. Çift Taraflı Muhasebe Sistemi (Double-Entry Bookkeeping Ledger)
Bankacılıkta para "yoktan var edilemez" veya "buharlaşıp yok olamaz". Her bir kuruş hareketinde iki kayıt oluşturulur:
- **DEBIT (Borç):** Gönderen hesaptan çıkan para.
- **CREDIT (Alacak):** Alıcı hesaba giren para.
- Her kayıtta `balanceAfter` (işlem anındaki bakiye fotoğrafı) saklanır. Böylece denetlenebilir, değiştirilemez ve anlık hesap ekstresi (audit trail) üretilir.

### 6. High-Level Payment Processing System (Gateway & Webhook Akışı)
Proje, kurumsal e-ticaret ve PSP ödeme akışını (Stripe, PayPal, Razorpay) 6 aşamalı bir yaşam döngüsüyle yönetir:

```
[ User & Frontend ]
        │ 1. Payment Request with Idempotency-Key
        ▼
[ Payment Service (Payment API) ]
        │ 2. Create "PENDING" Record & Insert Idempotency Key (PostgreSQL + Redis)
        ▼
[ Payment Gateway (External Provider) ]
        │ 3. Authorize Payment (Stripe / PayPal / Razorpay) -> Returns "AUTHORIZED"
        ▼
[ Webhook Listener (POST /webhooks) ]
        │ 4. Receive CAPTURED Webhook & Verify HMAC-SHA256 Signature
        ▼
[ Update Ledger (Payment Service) ]
        │ 5. Record AUTHORIZED / CAPTURED in Double-Entry Ledger (ACID Commit)
        ▼
[ Backend Services (Order Fulfillment) ]
          6. Trigger Inventory & Shipping via Transactional Outbox (Apache Kafka)
```

1. **User & Frontend:** İstemci, mükerrer ödemeleri engellemek için benzersiz `Idempotency-Key` başlığıyla `POST /api/v1/gateway/pay` isteğinde bulunur.
2. **PENDING Kaydı:** Veritabanında işlem `PENDING` olarak açılır ve Redis'te kilitlenir.
3. **Gateway Yetkilendirmesi:** Mock Gateway adaptörü dış sağlayıcıyı (Stripe PaymentIntent / PayPal Order) çağırarak `externalReferenceId` (`pi_stripe_...`) alır ve durumu `AUTHORIZED` yapar.
4. **Webhook Listener (`POST /api/v1/webhooks/gateway`):** Dış sağlayıcı ödemeyi başarıyla çektiğinde sisteme bir Webhook fırlatır. Webhook dinleyicisi **HMAC-SHA256 imza doğrulaması** ile isteğin sahte olmadığını ve Stripe/PayPal tarafından yollandığını doğrular (Timing attack korumalı constant-time karşılaştırma).
5. **Update Ledger:** Webhook teyidiyle işlem `CAPTURED` ve `SETTLED` yapılır; Borç ve Alacak ledger kayıtları oluşturulur.
6. **Trigger Order Fulfillment:** Transactional Outbox'a `PaymentCapturedEvent` yazılır; Kafka üzerinden envanter ve kargo (Inventory & Shipping) mikroservisleri kayıpsız tetiklenir.

---

## 🔐 Kimlik Doğrulama & Yetkilendirme (Keycloak)

Kimlik doğrulama (**authn**) uygulamanın dışında, **Keycloak** (OIDC kimlik sağlayıcısı) tarafından yapılır. `payment-api` bir **OAuth2 Resource Server**'dır: şifre görmez, oturum tutmaz (stateless, yalnızca `Authorization: Bearer <JWT>`); yetkilendirme (**authz**) ise uygulamanın içinde, rol ve hesap sahipliğine göre verilir.

```
[Tarayıcı / Konsol] ──(1) Giriş: Authorization Code + PKCE (S256)──► [Keycloak :8180, realm "mutabik"]
        │                                                                   │
        │◄──────────────(2) Access token (JWT, RS256)───────────────────────┘
        │
        │ (3) Authorization: Bearer <JWT>
        ▼
[payment-api :8080] ──(4) JWKS ile imza, iss, aud=mutabik-api, süre doğrulama──► [Keycloak (iç ağ: keycloak:8080)]
        │ (5) realm_access.roles → CUSTOMER / ADMIN,  customer_id → hesap sahipliği
        ▼
   AccountAccessPolicy (sahiplik kuralı tek yerde)
```

- **Issuer:** `http://localhost:8180/realms/mutabik` (Keycloak'un hostname'i sabittir; API anahtarları iç ağdan `jwk-set-uri` ile çeker, `iss` değerini sabit beklenen değerle doğrular).
- **Token claim'leri:** roller `realm_access.roles` içinde, müşteri kimliği `customer_id` claim'inde (kullanıcı kendisi değiştiremez, yalnızca yönetici düzenler), `aud` içinde `mutabik-api`.
- **Keycloak adresi:** `http://localhost:8180` (konsol client'ı: `mutabik-console`, public client + PKCE). Master realm yönetim konsolu: `admin` / `admin` (yalnızca yerel geliştirme).

### Demo Kullanıcıları (yalnızca yerel geliştirme için)
| Kullanıcı | Şifre | Rol | `customer_id` |
| :--- | :--- | :--- | :--- |
| `alice` | `alice123` | `CUSTOMER` | `CUST-ALICE` |
| `bob` | `bob123` | `CUSTOMER` | `CUST-BOB` |
| `charlie` | `charlie123` | `CUSTOMER` | `CUST-CHARLIE` |
| `admin` | `admin123` | `ADMIN` | yok |

### Erişim Matrisi
| Uç nokta | Anonim | CUSTOMER | ADMIN |
| :--- | :---: | :--- | :--- |
| `GET /`, `/index.html`, statik dosyalar | evet | evet | evet |
| `POST /api/v1/webhooks/gateway` (HMAC imzası) | evet | evet | evet |
| `GET /api/v1/accounts` | 401 | yalnız kendi hesapları | tümü |
| `GET /api/v1/accounts/{id}`, `/{id}/statement` | 401 | yalnız kendi (değilse 404) | tümü |
| `GET /api/v1/accounts/recipients` | 401 | evet | evet |
| `POST /api/v1/transfers` | 401 | kaynak hesap kendisinin (değilse 404) | 403 |
| `GET /api/v1/transfers` | 401 | yalnız kendi hesabının taraf olduğu transferler | tümü |
| `POST /api/v1/gateway/pay` | 401 | `customerAccountId` kendisinin (değilse 404) | 403 |
| `/api/v1/outbox/**` | 401 | 403 | evet |

- **Yönetici müşteri adına para göndermez** (transfer ve gateway/pay için 403): operasyon personelinin müşteri parasını hareket ettirememesi gerçek bankacılık kuralıdır.
- Sahibi olunmayan hesap ile var olmayan hesap aynı yanıtı (404) verir; hesap varlığı sızdırılmaz.
- **Actuator** ayrı portta (`8081`) çalışır ve compose tarafından dışarı yayınlanmaz; Prometheus iç ağdan `payment-api:8081` üzerinden çeker. Uygulama portunda (8080) actuator yoktur.
- Çevrimdışı mod (`.\run.ps1 -Local`, H2) güvenliği **kapatmaz**; yalnızca giriş için Keycloak container'ını başlatır. Otomatik testler Keycloak gerektirmez (taklit JWT).

### Demo Araçlarının Kaldırılması & İmzalı Webhook
`/api/v1/accounts/reset`, `/api/v1/simulation/concurrency` ve `/api/v1/webhooks/gateway/simulate` uçları kaldırıldı. Ödemeyi tamamlamanın tek yolu **HMAC-SHA256 imzalı webhook**'tur. Webhook secret'ı `PAYMENT_GATEWAY_WEBHOOK_SECRET` ortam değişkeninden okunur; koddaki varsayılan (`whsec_fintech_production_secret_key_99`) **yalnızca yerel geliştirme içindir**, gerçek bir ortamda mutlaka değiştirilmelidir.

```bash
SECRET="${PAYMENT_GATEWAY_WEBHOOK_SECRET:-whsec_fintech_production_secret_key_99}"
BODY='{"eventType":"payment_intent.succeeded","externalReferenceId":"pi_stripe_1fa7f92b51fb40cb","amount":350.00,"currency":"TRY","timestamp":"2026-11-13T12:00:00Z"}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')

curl -X POST http://localhost:8080/api/v1/webhooks/gateway \
  -H "Content-Type: application/json" \
  -H "X-Signature: $SIG" \
  -d "$BODY"
```
> `X-Signature`, ham JSON gövdesinin küçük harfli hex HMAC-SHA256 değeridir; gövde imzalandığı haliyle (bayt bayt aynı) gönderilmelidir. `externalReferenceId`, `POST /api/v1/gateway/pay` yanıtından alınır.

### Bakiyeleri Sıfırlama
Bakiyeleri başlangıca döndürmek için veritabanı yeniden oluşturulur: `docker compose -f docker/docker-compose.yml down -v` ardından `up -d` (H2 modunda uygulamayı yeniden başlatmak yeterlidir).

> **Mevcut Postgres volume'u uyarısı:** `transfers.idempotency_key` sütunu eski bir volume'da `varchar(120)` olarak kalır (`ddl-auto` mevcut sütunları genişletmez). Bu sürüme geçerken volume'u `docker compose -f docker/docker-compose.yml down -v` ile yeniden oluşturun (bu işlem bakiyeleri de sıfırlar).

---

## 📊 İzlenebilirlik (Observability & Metrics)

Mutabık, Micrometer ve Prometheus kütüphaneleriyle üretim seviyesinde metrik üretir:
- **`payment.transfer.duration`:** Transfer işlemlerinin süre dağılımı (p50, p90, p95, p99 gecikme yüzdelikleri).
- **`payment.transfers.total`:** `status=SUCCESS` ve `status=FAILED` etiketleriyle başarılı/başarısız işlem sayaçları.
- **Grafana Dashboard:** Önceden yapılandırılmış dashboard ile TPS, gecikmeler ve JVM heap bellek kullanımı canlı grafiklerle sunulur.

---

## 🖥️ Canlı Fintech Dark-Mode Yönetim Konsolu

Tarayıcı üzerinden `http://localhost:8080/index.html` adresine girildiğinde (Keycloak girişi ile; demo kullanıcıları için bkz. Kimlik Doğrulama) mülakatçıya tüm sistemi canlı olarak kanıtlayabileceğiniz modern bir arayüz açılır:

1. **Role göre ekranlar:** Başlıkta kullanıcı, rol rozeti ve "Çıkış" bulunur. **Müşteri** (`alice`, `bob`, `charlie`) yalnızca kendi hesabını, ekstresini ve transferlerini görür; transfer ve ödeme formlarını kullanır. **Yönetici** (`admin`) tüm hesapları, tüm transferleri ve Outbox panelini görür; para hareket ettiren formlar ona gösterilmez (sunucu da 403 döner).
2. **Canlı Hesap Kartları:** Yetki dahilindeki hesapların canlı bakiyeleri, maskeli IBAN ile (`TR33 •••• 0001`).
3. **Onaylı Para Transferi:** Alıcı, maskeli bir alıcı rehberinden seçilir; tutar Türkçe biçimde girilir (`1.250,50`). Para, ancak Gönderen / Alıcı / Tutar özetini gösteren onay penceresinde "Onayla ve gönder" seçildikten sonra hareket eder. Geçersiz tutarda hata, alanın hemen altında gösterilir.
4. **Idempotency Demosu ("Geliştirici detayları"):** Normal transfer her gönderimde yeni bir `Idempotency-Key` üretir. Kapalı "Geliştirici detayları" bölümündeki **"Aynı anahtarla tekrar gönder"** butonu son isteği aynı anahtarla yeniden yollar; yanıt önbellekten döner ve hesap ikinci kez borçlandırılmaz.
5. **Ödeme Geçidi:** Müşteri ödemeyi başlatır ve `AUTHORIZED` durumunda "ödeme sağlayıcısının onayı bekleniyor" olarak bekler. Tahsilat yalnızca sağlayıcının HMAC imzalı webhook'u ile tamamlanır (bkz. imzalı webhook örneği).
6. **Çift Taraflı Ekstre Tablosu:** Seçilen hesabın tüm Borç/Alacak ve bakiye hareketleri.
7. **Transactional Outbox & Kafka Monitörü (yönetici):** DB'den Kafka'ya akan event'lerin canlı izlenmesi.
8. **Eşzamanlılık Kanıtı (otomatik testler):** Canlı "stres simülatörü" ve bakiye sıfırlama uçları kaldırıldı. Eşzamanlılık garantileri otomatik testlerle kanıtlanır:
   - `ConcurrentTransferHttpTest`: aynı hesaptan 20 eşzamanlı transferin hepsinin başarılı olduğunu ve bakiyenin tam olarak düştüğünü doğrular.
   - `PaymentCoreIntegrationTest.testConcurrentDuplicateWebhooksDebitOnce`: aynı webhook'un eşzamanlı tekrarlarında hesabın yalnızca bir kez borçlandırıldığını doğrular.
   - `SecurityIntegrationTest` ve `AccessControlHttpTest`: yetkisiz erişimlerin engellendiğini doğrular.

**Erişilebilirlik ve mobil:** Form alanları etiketlerine bağlıdır; işlem sonuçları ekran okuyuculara `aria-live` ile duyurulur. Tüm metinler en az 12px ve WCAG AA kontrastındadır. İşletim sisteminde "hareketi azalt" seçiliyse animasyonlar kapanır. Dokunmatik ekranlarda her kontrol en az 44×44px'tir ve sayfa 375px genişlikte yatay kaydırma olmadan çalışır. Başlıktaki "API bağlı" rozeti sabit değildir; son API yanıtına göre değişir.

---

## 🚀 Hızlı Başlangıç & Kurulum

### Gereksinimler
- **Java 21 LTS**
- **Docker & Docker Compose**
- **Maven 3.9+** (veya repo içindeki `./mvnw.cmd`)

### 1. Tek Komutla TÜM Sistemi (Uygulama + Arayüz + Altyapı) Ayağa Kaldırma
```bash
docker compose -f docker/docker-compose.yml up -d --build
```
> **Not:** Bu tek komutla;
> - **Mutabık Core API & Canlı Web Konsolu** (`http://localhost:8080/index.html`),
> - **Keycloak 25** (`http://localhost:8180`, kimlik sağlayıcı),
> - **PostgreSQL 16** (`localhost:5433`),
> - **Redis 7** (`localhost:6379`),
> - **Apache Kafka** (`localhost:9092`),
> - **Prometheus** (`localhost:9090`),
> - **Grafana** (`localhost:3000`)
> hiçbir yerel bağımlılık (Java, Maven vb.) gerektirmeden tek seferde hazır hale gelir!

### 2. Alternatif: Yerel Geliştirici Modunda Çalıştırma (PowerShell)
```powershell
# Altyapıyı Docker ile başlat (Keycloak dahil):
.\run.ps1 -Docker

# Uygulamayı yerel JVM üzerinde başlat:
.\run.ps1

# Tüm modül testlerini koşmak için:
.\run.ps1 -Test

# In-Memory H2 ile denemek için (giriş için yalnızca Keycloak'u compose ile başlatır):
.\run.ps1 -Local
```

### 3. Servis Portları
| Servis | URL / Port | Kimlik Bilgileri |
| :--- | :--- | :--- |
| **Mutabık Konsolu & Web UI** | `http://localhost:8080/index.html` | Keycloak girişi (`alice` / `alice123` vb.) |
| **Keycloak** | `http://localhost:8180` | Demo kullanıcılar: bkz. Kimlik Doğrulama; master konsol `admin` / `admin` |
| **Actuator / Prometheus Metrics** | `8081` (dışarı yayınlanmaz, yalnızca iç ağ) | - |
| **Prometheus Server** | `http://localhost:9090` | - |
| **Grafana Dashboard** | `http://localhost:3000` | `admin` / `admin` |
| **PostgreSQL 16** | `localhost:5433` (DB: `settlement_db`) | `postgres` / `postgres` |
| **Redis 7** | `localhost:6379` | - |
| **Kafka Broker** | `localhost:9092` | - |

---

## 📡 Örnek cURL İstekleri

Tüm API uçları (webhook hariç) Bearer token ister. Konsol token'ı tarayıcıda otomatik alır; aşağıdaki örneklerde `$TOKEN`, geçerli bir access token'ı temsil eder (ör. tarayıcı geliştirici araçlarından alınan Alice token'ı).

### 1. Para Transferi (Idempotency Key ile)
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: tx-demo-12345" \
  -d '{
    "sourceAccountId": "11111111-1111-1111-1111-111111111111",
    "targetAccountId": "22222222-2222-2222-2222-222222222222",
    "amount": 150.00,
    "currency": "TRY",
    "description": "Fatura Ödemesi"
  }'
```
> Kaynak hesap token sahibine ait değilse `404` döner.

### 2. Hesap Ekstresi (Double-Entry Ledger)
```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/accounts/11111111-1111-1111-1111-111111111111/statement
```

### 3. E-Ticaret Ödeme Geçidi (Stripe / PayPal Pay & İmzalı Webhook)
```bash
# 1. Aşama: Gateway Yetkilendirmesi (Authorize)
curl -X POST http://localhost:8080/api/v1/gateway/pay \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: psp-order-9901" \
  -d '{
    "customerAccountId": "11111111-1111-1111-1111-111111111111",
    "merchantAccountId": "22222222-2222-2222-2222-222222222222",
    "amount": 350.00,
    "currency": "TRY",
    "gatewayType": "STRIPE",
    "customerEmail": "customer@shop.com",
    "description": "MacBook Stand Order #8821"
  }'

# 2. Aşama: Yanıttaki externalReferenceId ile imzalı CAPTURED webhook'unu gönderin
#    (bkz. Kimlik Doğrulama > Demo Araçlarının Kaldırılması & İmzalı Webhook)
```

---

## 🎓 Mülakat Hazırlık Soruları & Cevapları (Senior / Lead Java)

<details>
<summary><b>1. Neden Optimistic Locking yerine Pessimistic Locking tercih edildi?</b></summary>
<br>
Optimistic Locking (<code>@Version</code>), çakışma ihtimalinin düşük olduğu sistemlerde çok verimlidir. Ancak Black Friday indirimleri, bilet satışı veya popüler bir hesaba aynı saniyede yüzlerce para transferi geldiği senaryolarda Optimistic Lock yoğun <code>OptimisticLockException</code> fırlatır. İstemcilerin sürekli retry yapması CPU ve veritabanı bağlantı havuzunu boğar. Para transferi gibi mutlak tutarlılık gerektiren finansal işlemlerde PostgreSQL seviyesinde <code>PESSIMISTIC_WRITE</code> (<code>SELECT ... FOR UPDATE</code>) ile kuyruğa almak daha öngörülebilir ve güvenlidir.
</details>

<details>
<summary><b>2. Deterministik Kilit Sıralaması (Deterministic Lock Ordering) nedir ve neden zorunludur?</b></summary>
<br>
İki hesap arasındaki transferlerde deadlock riskini yok etmek için uygulanır. Eğer Thread 1 Alice'i kilitleyip Bob'u isterken, Thread 2 Bob'u kilitleyip Alice'i isterse deadlock oluşur. Her iki thread de hesapları ID'lerine göre sıralayıp (her zaman küçük olan ID önce kilitlenecek şekilde) kilitlerse dairesel bekleme (Circular Wait) döngüsü oluşamaz ve deadlock matematiksel olarak engellenir.
</details>

<details>
<summary><b>3. Clean Architecture'da UseCase neden Spring anotasyonları içermez?</b></summary>
<br>
Clean Architecture'ın temel kuralı (Dependency Rule), iş kurallarının (Business Rules) framework ve veritabanı gibi dış detaylara bağımlı olmamasıdır. <code>payment-application</code> ve <code>payment-domain</code> saf Java ile yazılır. <code>@Transactional</code> gibi altyapı detayları Decorator Pattern (<code>TransactionalTransferUseCaseDecorator</code>) ile dış API/Infrastructure katmanında sarmalanır. Böylece yarın Spring yerine Quarkus'a geçilse veya CLI aracı yazılsa çekirdek iş kurallarına dokunulmaz.
</details>

<details>
<summary><b>4. Dual-Write Problemi nedir ve Transactional Outbox bunu nasıl çözer?</b></summary>
<br>
Bir metot içinde hem veritabanını güncelleyip hem de Kafka'ya mesaj atmaya çalışırsak (Dual-Write), DB commit olup Kafka düşerse veya Kafka gönderip DB rollback olursa sistemler arası veri tutarsızlığı oluşur. Transactional Outbox Pattern'da event, veritabanına bakiye ile aynı ACID transaction içinde yazılır. Arka plandaki publisher event'i Kafka'ya ilettikten sonra işaretler (At-Least-Once Delivery).
</details>

<details>
<summary><b>5. Para hesaplamalarında neden Double veya Float kullanılmaz?</b></summary>
<br>
Floating-point sayı sistemleri (IEEE 754 standardı) ondalık sayıları ikilik tabanda tam olarak temsil edemez (Örn: <code>0.1 + 0.2 = 0.30000000000000004</code>). Finansal sistemlerde bir kuruşluk hata dahi yasal denetimlerde cezaya yol açar. Bu yüzden kesin hassasiyet sağlayan <code>BigDecimal</code> ve bankacılık yuvarlama standardı olan <code>RoundingMode.HALF_EVEN</code> (Banker's Rounding) zorunludur.
</details>

<details>
<summary><b>6. IDOR / BOLA nedir ve bu projede nasıl engellenir?</b></summary>
<br>
IDOR (Insecure Direct Object Reference), diğer adıyla BOLA (Broken Object Level Authorization): kullanıcının başkasına ait bir nesnenin kimliğini (ör. hesap UUID'si) isteğe koyarak o nesneye erişebilmesidir. Yalnızca "giriş yapmış mı?" ve "rolü ne?" kontrolü bunu engellemez. Mutabık'ta hesaplar <code>ownerCustomerId</code> taşır; token'daki <code>customer_id</code> claim'i ile eşleşmeyen hesaplar için tek bir yerde (<code>AccountAccessPolicy</code>) erişim reddedilir ve yanıt, var olmayan hesapla aynı <code>404</code> olur (hesap varlığı sızdırılmaz). Aynı <code>Idempotency-Key</code> ile başka bir kullanıcının önbelleğe alınmış yanıtı da okunamaz.
</details>

<details>
<summary><b>7. Neden kimlik doğrulama IdP'ye bırakıldı, yetkilendirme uygulamada tutuldu?</b></summary>
<br>
Şifre saklama, brute-force koruması, MFA, oturum ve anahtar rotasyonu ayrı bir uzmanlık alanıdır; Keycloak gibi olgun bir IdP'ye bırakmak saldırı yüzeyini küçültür ve "kendi auth'unu yazma" riskini ortadan kaldırır. API yalnızca imzalı, süresi geçerli, doğru <code>iss</code>/<code>aud</code> değerlerine sahip token'ı kabul eder. Buna karşın "bu kullanıcı bu hesaba dokunabilir mi?" sorusu iş alanı bilgisidir (hesap sahipliği, yöneticinin para hareket ettirememesi); bunu IdP bilemez, bu yüzden authz uygulamada, domain'e yakın tek bir politika sınıfında tutulur.
</details>

---

## 👨‍💻 Yazar & Lisans
**Utku Berki Baysal** &bull; [GitHub Profili](https://github.com/devutku) &bull; 2026

*Bu proje, kurumsal fintech ve bankacılık teknik mülakatlarında kıdemli mimari yetkinlikleri kanıtlamak amacıyla geliştirilmiş açık kaynaklı bir referans mimaridir.*
