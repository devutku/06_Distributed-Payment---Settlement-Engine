# 💳 AegisPay | Enterprise Distributed Payment & Settlement Engine
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

**AegisPay**, modern bankacılık, ödeme geçidi (Payment Gateway) ve fintech sistemlerinin (Adyen, Stripe, Wise, BKM vb.) yüksek hacimli ve sıfır hata toleranslı para transferi gereksinimlerine göre inşa edilmiş, **uçtan uca üretime hazır (production-grade)** bir dağıtık mutabakat ve transfer motorudur.

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
├── payment-api/             # Spring Boot bootstrap, REST Controller'lar, IdempotencyFilter, UI Konsolu
└── docker/                  # PostgreSQL 16, Redis 7, Kafka KRaft, Prometheus ve Grafana konfigürasyonları
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

**AegisPay Çözümü:**
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

Buna mikroservis mimarilerinde **Dual-Write Problemi** denir. AegisPay bu sorunu **Transactional Outbox Pattern** ile çözer:
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
- AegisPay, Spring Kafka `DefaultErrorHandler` ve `DeadLetterPublishingRecoverer` kullanarak üstel geri çekilme (`ExponentialBackOff`: 1s, 2s, 4s...) uygular.
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

## 📊 İzlenebilirlik (Observability & Metrics)

AegisPay, Micrometer ve Prometheus kütüphaneleriyle üretim seviyesinde metrik üretir:
- **`payment.transfer.duration`:** Transfer işlemlerinin süre dağılımı (p50, p90, p95, p99 gecikme yüzdelikleri).
- **`payment.transfers.total`:** `status=SUCCESS` ve `status=FAILED` etiketleriyle başarılı/başarısız işlem sayaçları.
- **Grafana Dashboard:** Önceden yapılandırılmış dashboard ile TPS, gecikmeler ve JVM heap bellek kullanımı canlı grafiklerle sunulur.

---

## 🖥️ Canlı Fintech Dark-Mode Yönetim Konsolu

Tarayıcı üzerinden `http://localhost:8080/index.html` adresine girildiğinde mülakatçıya tüm sistemi canlı olarak kanıtlayabileceğiniz modern bir arayüz açılır:

1. **Canlı Hesap Kartları:** Alice, Bob, Charlie ve Takas Havuzunun canlı bakiyeleri.
2. **Idempotency Test Paneli:** Aynı Idempotency Key ile iki kez transfer yapıldığında ikinci isteğin önbellekten sıfır çift çekimle döndüğünün görsel kanıtı.
3. **Eşzamanlılık Stres Simülatörü (The Killer Demo):**
   - Charlie hesabında sadece **250 TL** varken, **10 eşzamanlı thread** aynı nanosaniyede **100 TL** çekmeye çalışır (İstenen: 1.000 TL).
   - Pessimistic Row Lock sayesinde tam 2 işlem onaylanır, 8 işlem reddedilir, Charlie'nin son bakiyesi tam olarak **50.00 TL** kalır. Eksi bakiye olasılığı sıfırdır!
4. **Çift Taraflı Ekstre Tablosu:** Seçilen hesabın tüm Borç/Alacak ve bakiye hareketleri.
5. **Transactional Outbox & Kafka DLQ Monitörü:** DB'den Kafka'ya akan event'lerin canlı izlenmesi.

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
> - **AegisPay Core API & Canlı Web Konsolu** (`http://localhost:8080/index.html`),
> - **PostgreSQL 16** (`localhost:5433`),
> - **Redis 7** (`localhost:6379`),
> - **Apache Kafka** (`localhost:9092`),
> - **Prometheus** (`localhost:9090`),
> - **Grafana** (`localhost:3000`)
> hiçbir yerel bağımlılık (Java, Maven vb.) gerektirmeden tek seferde hazır hale gelir!

### 2. Alternatif: Yerel Geliştirici Modunda Çalıştırma (PowerShell)
```powershell
# Altyapıyı Docker ile başlat:
.\run.ps1 -Docker

# Uygulamayı yerel JVM üzerinde başlat:
.\run.ps1

# Tüm modül testlerini koşmak için:
.\run.ps1 -Test

# Docker olmadan sadece In-Memory H2 ile denemek için:
.\run.ps1 -Local
```

### 3. Servis Portları
| Servis | URL / Port | Kimlik Bilgileri |
| :--- | :--- | :--- |
| **AegisPay Konsolu & Web UI** | `http://localhost:8080/index.html` | - |
| **Prometheus Metrics** | `http://localhost:8080/actuator/prometheus` | - |
| **Prometheus Server** | `http://localhost:9090` | - |
| **Grafana Dashboard** | `http://localhost:3000` | `admin` / `admin` |
| **PostgreSQL 16** | `localhost:5433` (DB: `settlement_db`) | `postgres` / `postgres` |
| **Redis 7** | `localhost:6379` | - |
| **Kafka Broker** | `localhost:9092` | - |

---

## 📡 Örnek cURL İstekleri

### 1. Para Transferi (Idempotency Key ile)
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
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

### 2. Hesap Ekstresi (Double-Entry Ledger)
```bash
curl -X GET http://localhost:8080/api/v1/accounts/11111111-1111-1111-1111-111111111111/statement
```

### 3. Eşzamanlılık Stres Simülasyonu (10 Thread)
```bash
curl -X POST http://localhost:8080/api/v1/simulation/concurrency \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "33333333-3333-3333-3333-333333333333",
    "targetAccountId": "22222222-2222-2222-2222-222222222222",
    "concurrencyCount": 10,
    "amountPerTransfer": 100.00
  }'
```

### 4. E-Ticaret Ödeme Geçidi (Stripe / PayPal Pay & Webhook)
```bash
# 1. Aşama: Gateway Yetkilendirmesi (Authorize)
curl -X POST http://localhost:8080/api/v1/gateway/pay \
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

# 2. Aşama: Otomatik İmzalanmış CAPTURED Webhook Simülasyonu
curl -X POST http://localhost:8080/api/v1/webhooks/gateway/simulate \
  -H "Content-Type: application/json" \
  -d '{
    "externalReferenceId": "pi_stripe_1fa7f92b51fb40cb",
    "amount": 350.00,
    "currency": "TRY"
  }'
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

---

## 👨‍💻 Yazar & Lisans
**Utku Berki Baysal** &bull; [GitHub Profili](https://github.com/devutku) &bull; 2026

*Bu proje, kurumsal fintech ve bankacılık teknik mülakatlarında kıdemli mimari yetkinlikleri kanıtlamak amacıyla geliştirilmiş açık kaynaklı bir referans mimaridir.*
