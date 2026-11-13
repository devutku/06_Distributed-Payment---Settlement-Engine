# Kimlik Doğrulama ve Yetkilendirme Tasarımı (Keycloak + Hesap Sahipliği)

Tarih: 2026-11-13 · Durum: onaylandı

## 1. Amaç ve neden

Mutabık bir portföy / mülakat vitrini. Projenin değeri, gerçek ödeme sistemlerinin
dertlerini (idempotency, deadlock, outbox) gerçekçi biçimde çözmesinde. Şu an hiçbir
uç noktada kimlik doğrulama yok: API'ye erişebilen herkes para transfer edebiliyor,
bakiyeleri sıfırlayabiliyor, `/webhooks/gateway/simulate` ile istediği ödemeyi
onaylatabiliyor.

Hedef: kodu açan bir mülakatçının "bu, gerçek bir bankanın yapacağı şeye benziyor"
diyeceği bir kimlik doğrulama ve yetkilendirme katmanı. Kimlik doğrulama (kim olduğun)
uzman bir sisteme bırakılır; uygulama yetkilendirmeye (neyi yapabileceğin) odaklanır.

## 2. Hedefler, hedef olmayanlar, anti-hedefler

**Hedefler**
- Kimlik sağlayıcı olarak Keycloak; API bir OAuth2 Resource Server olarak yalnızca JWT doğrular.
- İki rol: `CUSTOMER` ve `ADMIN`.
- **Hesap sahipliği:** müşteri yalnızca kendi hesaplarından para gönderir, yalnızca kendi
  hesaplarını, ekstrelerini ve transferlerini görür.
- Yönetici uç noktaları (outbox izleme) yalnızca `ADMIN`.
- **Demo araçları kaldırılır:** `POST /accounts/reset`, `POST /simulation/concurrency` ve
  `POST /webhooks/gateway/simulate` uç noktaları ve konsoldaki ilgili paneller silinir
  (bkz. 5.8). Hiçbir rol, ödeme sağlayıcısı olmadan ödeme onaylatamaz veya bakiyeleri ezemez.
- Web konsolu Keycloak üzerinden giriş yapar (OIDC Authorization Code + PKCE) ve her
  isteğe `Authorization: Bearer` ekler.
- Mevcut her şey çalışmaya devam eder: webhook HMAC akışı, Prometheus metrikleri, 25 test.

**Hedef olmayanlar**
- Kayıt olma, şifre sıfırlama, MFA, e-posta doğrulama (Keycloak'ta var, kapsam dışı).
- Bir müşterinin birden fazla hesabı için arayüz akışı (model destekler, konsol bir hesap varsayar).
- Production ölçeğinde Keycloak kurulumu (HA, harici veritabanı, TLS).

**Anti-hedefler (çalışsa bile başarısızlık sayılır)**
- Başka müşterinin hesabından para gönderebilmek veya verisini görebilmek (IDOR/BOLA).
- Yetki kontrolünün yalnızca konsolda (butonları gizleyerek) yapılması; kural sunucuda olmalı.
- Sahiplik kuralının controller'lara dağılması; kural application katmanında, tek yerde olmalı.
- Webhook'un Keycloak token'ı istemesi (ödeme sağlayıcısı token alamaz; HMAC yeterli ve doğru).
- Var olmayan hesap ile başkasının hesabının ayırt edilebilmesi (hesap varlığını sızdırmak).

## 3. Mevcut durum (kısa)

- `payment-api` controller'ları: `accounts` (liste, tekil, ekstre, `reset`), `transfers`
  (oluştur, son 50), `gateway/pay`, `webhooks/gateway` (+`/simulate`), `outbox`
  (liste, `publish-now`, `kafka-status`), `simulation/concurrency`.
- Bazı okuma uç noktaları application katmanını atlayıp doğrudan Spring Data repository
  kullanıyor (`AccountController.getStatement`, `TransferController.getRecentTransfers`,
  `OutboxController`).
- `Account.customerNumber` bir müşteri kimliği değil; IBAN + isim içeren serbest metin
  (`"TR330006100511123456780001 (Alice Smith)"`). Hesabı bir müşteriye bağlayan alan yok.
- Seed: Alice, Bob, Charlie (müşteri hesapları) ve Clearing Treasury (banka iç hesabı).
- Idempotency cache anahtarı yalnızca `Idempotency-Key` başlığı; isteği yapan kişiden bağımsız.
- Prometheus `/actuator/prometheus` adresini 8080 portundan kimlik bilgisi olmadan çekiyor.

## 4. Yaklaşımlar

| | Yaklaşım | Artı | Eksi |
|---|---|---|---|
| **1 (seçildi)** | Keycloak container'ı + API Resource Server | Gerçek dünyadaki ayrım; "kendi kimlik sistemimi yazmadım" mülakatta artı | ~500 MB container, 20-30 sn açılış, realm JSON'u okunaksız |
| 2 | Uygulama kendi JWT'sini üretir (`/auth/login`, BCrypt) | Ek altyapı yok, her şey kodda | "Neden kendi auth'unu yazdın?" sorusu; şifre/anahtar yönetimi bizde |
| 3 | Spring Authorization Server | Standart OAuth2 sunucusu | En zahmetli yapılandırma, vitrin değeri Keycloak'tan fazla değil |

Erişim modeli olarak "yalnızca roller" yerine "roller + hesap sahipliği" seçildi:
sahiplik olmadan IDOR açığı kalır ve mülakatta ilk eleştiri oradan gelir.

## 5. Tasarım

### 5.1 Keycloak

- `docker-compose.yml`'a `keycloak` servisi (resmi `quay.io/keycloak/keycloak` imajı,
  `start-dev --import-realm`), host portu **8180**.
- Realm `docker/keycloak/mutabik-realm.json` ile otomatik yüklenir:
  - Realm: `mutabik`.
  - Realm rolleri: `CUSTOMER`, `ADMIN`.
  - Client `mutabik-console`: public client, standard flow + PKCE (S256), redirect URI
    `http://localhost:8080/*`, web origin `http://localhost:8080`.
  - Token'a eklenen claim'ler (protocol mapper ile):
    - `customer_id`: kullanıcı attribute'u (`alice` → `CUST-ALICE` gibi).
    - `aud` içinde `mutabik-api` (audience mapper).
    - Realm rolleri standart `realm_access.roles` içinde.
  - Demo kullanıcıları (şifreler demo amaçlı, README'de yazılı):
    `alice`, `bob`, `charlie` → `CUSTOMER`; `admin` → `ADMIN` (`customer_id` yok).
- **Issuer tutarlılığı:** tarayıcı token'ı `http://localhost:8180` üzerinden alır,
  API container'ı Keycloak'a `http://keycloak:8080` üzerinden erişir. Keycloak'ın hostname'i
  `http://localhost:8180` olarak sabitlenir; böylece `iss` her yerde aynıdır. API, anahtarları
  ayrı bir `jwk-set-uri` ile çeker, `iss` değerini sabit beklenen değerle doğrular.

### 5.2 API güvenliği (`payment-api`)

- Bağımlılık: `spring-boot-starter-oauth2-resource-server`.
- Stateless: oturum yok, CSRF kapalı (yalnızca Bearer token; cookie kullanılmıyor).
- JWT doğrulama: imza (JWKS), `iss`, `aud` içinde `mutabik-api`, süre.
- `realm_access.roles` → `ROLE_CUSTOMER` / `ROLE_ADMIN` (özel `JwtAuthenticationConverter`).
- Hatalar RFC 7807 (`application/problem+json`), mevcut exception handler ile aynı biçimde:
  token yok/geçersiz → **401**, rol yetersiz → **403**, sahip olunmayan hesap → **404**.
  *(Builder kararı)* "Hesap bulunamadı" bugün `IllegalArgumentException` → 400 dönüyor; ayrı bir
  `AccountNotFoundException` eklenir ve 404'e eşlenir, böylece sahiplik ihlali ile var olmayan
  hesap aynı yanıtı verir.

**Erişim matrisi**

| Uç nokta | Anonim | CUSTOMER | ADMIN |
|---|---|---|---|
| `GET /`, `/index.html`, statik dosyalar | ✓ | ✓ | ✓ |
| `POST /api/v1/webhooks/gateway` (HMAC) | ✓ | ✓ | ✓ |
| `GET /api/v1/accounts` | 401 | yalnız kendi hesapları | tümü |
| `GET /api/v1/accounts/{id}`, `/{id}/statement` | 401 | yalnız kendi (değilse 404) | tümü |
| `GET /api/v1/accounts/recipients` (yeni) | 401 | ✓ | ✓ |
| `POST /api/v1/transfers` | 401 | kaynak hesap kendisinin (değilse 404) | 403 |
| `GET /api/v1/transfers` | 401 | yalnız kendi hesabının taraf olduğu transferler | tümü |
| `POST /api/v1/gateway/pay` | 401 | `customerAccountId` kendisinin (değilse 404) | 403 |
| `/api/v1/outbox/**` | 401 | 403 | ✓ |
| ~~`POST /accounts/reset`, `/simulation/**`, `/webhooks/gateway/simulate`~~ | kaldırıldı (404) | | |

- **Yönetici müşteri adına para göndermez** (transfer ve gateway/pay için 403). Operasyon
  personelinin müşteri parasını hareket ettirememesi gerçek bankacılık kuralıdır. Demo araçları
  kaldırıldığı için yöneticinin para hareket ettirebileceği hiçbir yol kalmaz.
- **Actuator:** management ayrı porta taşınır (`management.server.port: 8081`), bu port
  dışarı açılmaz (compose'da `ports` yok; yalnızca iç ağ). Prometheus `payment-api:8081`
  adresinden kimlik bilgisi olmadan çeker. Uygulama portu 8080'de actuator yoktur.
  Yerelde (IDE'den çalıştırma) 8081 localhost'ta erişilebilir kalır.

### 5.3 Hesap sahipliği (domain + application)

- `Account`'a `ownerCustomerId` (nullable `String`) eklenir. `null` = banka iç hesabı
  (Clearing Treasury); hiçbir müşteriye ait değildir.
- Yeni değer nesnesi `Requester` (application katmanı):
  `Requester.customer(customerId)`, `Requester.admin()`, `Requester.system()`.
  - API katmanı JWT'den `Requester` üretir (`customer_id` claim'i + roller).
  - `system()` yalnızca sunucu içi akışlar içindir (HMAC ile doğrulanmış webhook'un
    müşteri hesabını borçlandırması); HTTP isteğindeki bir kullanıcıdan asla üretilmez.
- **Kural tek yerde:** application katmanında bir `AccountAccessPolicy`
  (veya eşdeğer servis):
  - `canMoveFundsFrom(requester, account)`: müşteri ve sahip → evet; `system` → evet;
    `admin` → hayır.
  - `canView(requester, account)`: sahip veya `admin` → evet.
  - İhlal → hesap yokmuş gibi "bulunamadı" hatası (404).
- Use case'ler ve sorgular `Requester` alır:
  `InitiateTransferCommand`, `GatewayPaymentCommand`, `GetAccountBalanceQuery`,
  `GetAccountStatementQuery`, son transferler sorgusu.
- Repository'yi doğrudan kullanan okuma uç noktaları (`getStatement`, `getRecentTransfers`)
  application katmanındaki sorgulara taşınır ki sahiplik kontrolü atlanamasın.
  `OutboxController` yalnızca ADMIN olduğu için olduğu gibi kalabilir.
- Alıcı hesap herhangi bir hesap olabilir (başkasına para göndermek serbest).
- **Alıcı seçimi:** müşteri artık tüm hesapları listeleyemez. Yeni
  `GET /api/v1/accounts/recipients` uç noktası transfer yapılabilecek hesapları
  yalnızca `id`, maskeli isim ("Bob J.") ve maskeli IBAN ("TR33 **** 0002") ile döner;
  bakiye ve tam IBAN dönmez. Konsoldaki "Hedef Hesap" listesi buradan dolar.
  *(Builder kararı)* Liste = sahibi olan (müşteri) ve `ACTIVE` hesaplar, çağıranın kendi
  hesapları hariç. Banka iç hesapları (Clearing Treasury, sahibi `null`) listede yoktur ve
  müşteri tarafından hedef olarak kullanılamaz (404). Bu liste bilerek bir "alıcı rehberi"dir:
  listedeki hesapların varlığı müşteriye açıktır; 404 ayırt edilemezliği listede olmayan
  hesapları (iç hesaplar, var olmayan ID'ler) korur.
- Seed (`DataLoader`): Alice → `CUST-ALICE`, Bob → `CUST-BOB`, Charlie → `CUST-CHARLIE`,
  Clearing → `null`. Keycloak kullanıcılarının `customer_id` değerleri bunlarla eşleşir.
- Mevcut veritabanı: `ddl-auto: update` yeni kolonu ekler; mevcut satırlarda değer `null`
  olur. `DataLoader`, sahibi boş müşteri hesaplarını seed tablosuna göre doldurur
  (yalnızca demo ortamı için; production migration'ı kapsam dışı).

### 5.4 Idempotency anahtarının kişiye bağlanması

*(Builder kararları)* Yanıtlarda istemcinin gönderdiği orijinal anahtar döner, birleşik değer
değil. Birleşik değer `transfers.idempotency_key` kolonunun 120 karakter sınırını aşabileceği
için kolon 200 karaktere genişletilir.

Kimlik doğrulama gelince yeni bir sızıntı oluşur: Bob, Alice'in `Idempotency-Key`'ini
tahmin eder/görürse cache'deki Alice yanıtını (hesap ID'leri, tutar) alır. Cache ve kilit
anahtarı istek sahibine göre ad alanına alınır: `customerId:key` (system için `system:key`).
Transfer tablosundaki benzersiz `idempotency_key` kısıtı da aynı birleşik değeri saklar.

### 5.5 Web konsolu (`index.html`)

- `keycloak-js` (CDN, sabit sürüm) ile sayfa açılışında `login-required` + PKCE.
- Tüm `fetch` çağrıları tek bir yardımcıdan geçer: token'ı gerekirse yeniler,
  `Authorization: Bearer` ekler; 401'de yeniden girişe yönlendirir.
- Başlıkta giriş yapan kullanıcı, rolü ve "Çıkış" butonu.
- Müşteri görünümü: kaynak hesap = kendi hesabı (sabit), hedef = `recipients` listesi.
  Ödeme geçidi panelinde yalnızca "öde" adımı kalır; ödeme `AUTHORIZED` durumunda
  "ödeme sağlayıcısının onayı bekleniyor" olarak gösterilir. Outbox paneli gizli.
- Yönetici görünümü: tüm hesaplar, tüm transferler ve outbox paneli; transfer ve ödeme
  formları gizli (yönetici müşteri adına para göndermez).
- Reset butonu, eşzamanlılık simülasyonu paneli (hazır ayarlar dahil) ve webhook simülasyonu
  butonu konsoldan silinir.
- Gizleme yalnızca kullanım kolaylığıdır; kural sunucudadır (anti-hedef).

### 5.6 Testler

- `spring-security-test` ile `jwt()` post-processor; testler Keycloak'a ihtiyaç duymaz.
- Mevcut 25 test, uygun kullanıcı/rol token'larıyla geçmeye devam eder.
- Yeni testler en az şunları kapsar:
  - Token yok → 401; geçersiz `aud`/`iss` → 401.
  - Alice, Bob'un hesabından transfer → 404; Bob'un ekstresi → 404; var olmayan hesap → 404
    (ikisi ayırt edilemez).
  - Müşteri `GET /accounts` → yalnız kendi hesabı; `GET /transfers` → yalnız kendi transferleri.
  - Müşteri yönetici uç noktalarına → 403; yönetici transfer → 403.
  - Webhook token olmadan, geçerli HMAC ile → 200; geçersiz imza ile → reddedilir.
  - Kaldırılan uç noktalar (`/accounts/reset`, `/simulation/concurrency`,
    `/webhooks/gateway/simulate`) → 404.
  - `/simulate` kullanan mevcut test (`testPaymentGatewayAndWebhookFlow`), webhook'u
    testte HMAC ile imzalayıp gerçek `/webhooks/gateway` uç noktasına gönderecek şekilde değişir.
    Eşzamanlılık davranışı mevcut eşzamanlı testlerde doğrulanmaya devam eder.
  - Aynı `Idempotency-Key` ile Bob, Alice'in cache'lenmiş yanıtını alamaz.
  - `AccountAccessPolicy` için birim testleri (application katmanı, Spring yok).
- Uçtan uca doğrulama (otomatik test değil): compose ile Keycloak'u açıp konsoldan
  alice ve admin olarak giriş, transfer, yetkisiz işlem denemesi.

### 5.7 Dokümantasyon

- README: kimlik doğrulama bölümü (mimari şema, erişim matrisi, demo kullanıcıları ve
  şifreleri, Keycloak adresi), "Mülakat Kazanım Noktaları"na IDOR/BOLA ve
  "authn'i IdP'ye bırak, authz uygulamada" maddeleri.
- `run.ps1 -Docker` Keycloak'u da başlatır.
- *(Builder kararı)* Çevrimdışı mod (`run.ps1 -Local`, H2) için güvenliği kapatan bir
  "geliştirici modu" **yoktur**; yanlış ortamda açık kalırsa tüm API korumasız kalır.
  `-Local`, H2 ile çalışır ama giriş için yalnızca Keycloak container'ını compose ile başlatır.
  Otomatik testler Keycloak'a ihtiyaç duymaz (taklit JWT); güvenlik zinciri testlerde de açıktır.
- *(Builder kararı)* `customer_id` Keycloak'ta kullanıcının kendisinin değiştiremeyeceği,
  yalnızca yöneticinin düzenleyebildiği bir attribute'tur (user profile ayarı).
- *(Builder kararı)* `prometheus.yml` hedefleri `payment-api:8081` ve
  `host.docker.internal:8081` olur.

### 5.8 Demo araçlarının kaldırılması

- Silinir: `SimulationController`, `AccountController.resetBalances`,
  `WebhookController.simulateWebhook`, konsoldaki karşılıkları.
- Ödemeyi tamamlamanın tek yolu imzalı webhook'tur. README, yerel geliştirme secret'ı ile
  imzalanmış bir webhook'un `curl` + `openssl` ile nasıl gönderileceğini gösterir.
  Webhook secret'ı ortam değişkeninden okunur (`PAYMENT_GATEWAY_WEBHOOK_SECRET`); koddaki
  varsayılan değer yalnızca yerel geliştirme içindir ve README'de öyle belirtilir.
- Bakiyeleri başlangıca döndürmek: veritabanını yeniden oluşturmak
  (`docker compose down -v` ardından `up`; H2'de uygulamayı yeniden başlatmak). README'ye yazılır.
- README'deki eşzamanlılık simülasyonu ve "100+ eşzamanlı transfer" vitrin anlatımı,
  otomatik testlere (`testConcurrentDuplicateWebhooksDebitOnce` vb.) yönlendirecek şekilde güncellenir.

## 6. Kısıtlar

- Java 21, Spring Boot 3.3.5; mevcut çok modüllü Clean Architecture korunur.
  `payment-domain` ve `payment-application` Spring'e bağımlı olmaz.
- Testler Docker gerektirmez (H2 + taklit JWT).
- Harici script'ler yalnızca sabit sürümle CDN'den yüklenir.

## 7. Builder'a bırakılanlar

- Keycloak imaj sürümü ve realm JSON'unun ayrıntıları (token ömürleri vb.), yukarıdaki
  roller/claim'ler/client ayarları sağlandığı sürece.
- `Requester`'ın komutlara nasıl taşındığı (alan mı, ayrı parametre mi).
- Konsoldaki görsel düzen; mevcut tasarım dili korunarak.
- Maskeleme biçimleri (isim, IBAN).
