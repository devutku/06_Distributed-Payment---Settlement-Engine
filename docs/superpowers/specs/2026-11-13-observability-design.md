# Gözlemlenebilirlik ve Bellek Analizi Tasarımı

Tarih: 2026-11-13 · Durum: onaylandı

## Amaç
Mutabık bir portföy/mülakat vitrini. "Yüksek hacim" ve "üretime hazır" iddialarını görünür ve ölçülebilir kanıtlarla desteklemek:
bir transferin tüm yolculuğunu tek bir izde göstermek, iş metriklerini ve alarmları hazır sunmak, gecikmeyi yük altında ölçmek
ve bellek sorunlarını Eclipse MAT ile analiz edebilmek.

## Hedefler
1. **Dağıtık izleme:** Micrometer Tracing + OpenTelemetry köprüsü, OTLP ile Jaeger'a (`jaegertracing/all-in-one`, arayüz `:16686`, OTLP HTTP `:4318`).
   HTTP, JDBC ve Kafka (gönderim + tüketim) span'leri. Outbox satırına W3C `traceparent` yazılır, yayımlama sırasında geri yüklenir;
   böylece API → DB → outbox → Kafka → tüketici **tek bir iz** olur.
2. **İş metrikleri:** transfer, ödeme ve webhook sonuçları (outcome etiketiyle), bekleyen outbox sayısı, yayımlama hataları, DLQ mesajları,
   tüketicide atlanan kopyalar. Grafana dashboard panelleri.
3. **Alarmlar:** Prometheus kuralları — outbox birikmesi, yayımlama hatası, DLQ mesajı, transfer hata oranı > %5, geçersiz imza patlaması,
   transfer p95 > 500 ms. Alertmanager yok; alarmlar Prometheus arayüzünde.
4. **Yük testi:** k6 (Docker ile, kurulum yok). alice ↔ bob küçük tutarlı iki yönlü transferler + bakiye okumaları. Eşikler: p95 < 500 ms,
   hata oranı < %1. Ölçülen sonuçlar README'de.
5. **Eclipse MAT:** OOM'da otomatik heap dump (`-XX:+HeapDumpOnOutOfMemoryError`, `./dumps`), elle dump scripti (`jcmd GC.heap_dump`),
   MAT'ı headless çalıştırıp "Leak Suspects" raporu üreten script (kullanıcı MAT'ı kurar, `MAT_HOME`). README'de yorumlama rehberi.

## Hedef olmayanlar
Alertmanager/bildirim kanalları, log toplama (Loki/ELK), Tempo, üretim ölçeğinde izleme kurulumu, MAT'ın otomatik indirilmesi,
yük altında gerçek bellek analizi yapılması (altyapı kurulur, analiz kullanıcıya kalır).

## Anti-hedefler
- İzlemenin testleri yavaşlatması veya testlerde dışarıya bağlanmaya çalışması (testlerde izleme kapalı).
- Konsol client'ının (`mutabik-console`) doğrudan şifreyle token alır hale gelmesi.
- Metriklerde müşteri kimliği, hesap ID'si veya tutar gibi yüksek kardinaliteli/kişisel verinin etiket olması.
- `payment-domain`/`payment-application`'ın Micrometer veya Spring'e bağımlı hale gelmesi (metrikler API/altyapı katmanında).

## Kararlar
- Jaeger (tek container, kendi arayüzü) — Tempo yerine.
- k6 token'ı için realm'e ayrı, gizli anahtarlı `mutabik-loadtest` client'ı (yalnızca doğrudan şifre girişi; yalnızca yerel yük testi için
  belgelenir). Konsol client'ı PKCE-only kalır.
- MAT kullanıcı tarafından kurulur; scriptler `MAT_HOME` bekler.
- Mevcut `payment.transfers.total` sayaçları yeni `mutabik.transfers` (outcome etiketli) ile değiştirilir; `payment.transfer.duration` kalır.

## Builder'a bırakılanlar
Sürüm seçimleri (sabit sürüm şartıyla), dashboard yerleşimi, k6 senaryosunun VU/süre değerleri (eşikler sabit).
