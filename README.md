# 💳 Distributed Payment & Settlement Engine

Enterprise-grade Distributed Payment and Settlement Engine designed for banking and fintech technical scenarios.

## 📌 Mimari Odak Noktaları (Core Architectural Pillars)
- **Idempotency Key Pattern:** Ağ gecikmeleri ve tekrar eden isteklerde mükerrer ödemeleri önleme (Redis / DB).
- **Distributed Locking & Concurrency:** Çift harcama (Double-spending) ve bakiye yarış durumlarını (Race Condition) önlemek için Redisson / Pessimistic Locking.
- **Transactional Outbox & Saga Pattern:** Dağıtık servisler arası veri tutarlılığı (Event-driven consistency via Kafka/RabbitMQ).
- **Double-Entry Bookkeeping (İki Taraflı Kayıt Sistemi):** Finansal mutabakat ve denetlenebilir defter-i kebir (Ledger) mimarisi.
- **Automated Settlement & Reconciliation:** Gün sonu mutabakatı (EOD Settlement Batch Job) ve uyuşmazlık çözümü.
