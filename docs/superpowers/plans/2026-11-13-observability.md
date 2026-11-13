# Observability & Heap-Dump Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add end-to-end distributed tracing (incl. across the outbox), business metrics with Prometheus alerts, a k6 load test, and Eclipse MAT heap-dump tooling.

**Architecture:** Micrometer Tracing with the OTel bridge exports OTLP to Jaeger; the outbox row stores the W3C `traceparent` so the scheduled publisher continues the original trace into Kafka. Business metrics live in the API/infrastructure layers (application layer stays library-free). k6 and MAT run as tools outside the app.

**Tech Stack:** Spring Boot 3.3.5, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, datasource-micrometer-spring-boot 1.0.5, Jaeger all-in-one 1.62.0, Prometheus 2.53 rules, Grafana 11.1, k6 (grafana/k6:0.54.0), JDK `jcmd`, Eclipse MAT (user-installed).

**Spec:** `docs/superpowers/specs/2026-11-13-observability-design.md`

## Global Constraints

- No new dependency in `payment-domain` or `payment-application`.
- Tests never export traces: `management.tracing.enabled=false` in `payment-api/src/test/resources/config/application.properties` (a `config/` file adds to, not shadows, the main `application.yml`).
- Default OTLP endpoint `http://localhost:4318/v1/traces`; compose overrides with `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://jaeger:4318/v1/traces`. Sampling probability `1.0`.
- Metric names (Prometheus form in parentheses): `mutabik.transfers` (`mutabik_transfers_total`, tag `outcome` ∈ success|replay|insufficient_funds|not_found|rejected|error), `mutabik.gateway.payments` (tag `outcome` ∈ authorized|not_found|rejected|error), `mutabik.webhooks` (tag `outcome` ∈ captured|replay|invalid_signature|error), gauge `mutabik.outbox.pending`, `mutabik.outbox.publish.failures`, `mutabik.kafka.dlq.messages`, `mutabik.kafka.duplicates.skipped`. Keep `payment.transfer.duration`; remove the old `payment.transfers.total` counters. No account ids, customer ids or amounts as tags.
- k6 thresholds: `http_req_duration{scenario:transfers}` p(95) < 500 ms; `http_req_failed` rate < 0.01.
- `mutabik-console` stays PKCE-only; new confidential client `mutabik-loadtest` (direct access grants only), secret documented as local-only.
- Heap dumps go to `dumps/` (git-ignored, also `*.hprof`).
- Build/test prefix (Git Bash): `export JAVA_HOME="/c/Program Files/BellSoft/LibericaJDK-21" PATH="/c/Program Files/BellSoft/LibericaJDK-21/bin:$PATH"; ./mvnw -B`. Docker via PowerShell after refreshing `$env:Path` from Machine+User.

## Review Focus

- A request with no active span (e.g. scheduler-only path) must still write the outbox row (null `trace_parent`) and publish normally. → Task 2 test.
- A malformed stored `traceparent` must not break publishing (start a new root span instead). → Task 2 test.
- Business rejections (insufficient funds, not found) must not count as `error`, or the error-rate alert fires on normal customer behavior. → Task 3 test.
- Tracing must not make the test suite try to reach `localhost:4318`. → Task 1 (test config) + full suite run.
- The k6 run must leave alice and bob balances unchanged in total (two-way transfers). → Task 4 verification.

---

### Task 1: Tracing foundation (HTTP, JDBC, Kafka → Jaeger)

**Files:** `payment-api/pom.xml`, `payment-infrastructure/pom.xml` (if Kafka/JDBC config lives there), `payment-api/src/main/resources/application.yml`, `payment-api/src/test/resources/config/application.properties` (new), `docker/docker-compose.yml` (jaeger service; payment-api env).

- [ ] Add `io.micrometer:micrometer-tracing-bridge-otel`, `io.opentelemetry:opentelemetry-exporter-otlp` (Boot-managed versions) and `net.ttddyy.observation:datasource-micrometer-spring-boot:1.0.5` to payment-api.
- [ ] `application.yml`: `management.tracing.sampling.probability: 1.0`, `management.otlp.tracing.endpoint: http://localhost:4318/v1/traces`, `spring.kafka.template.observation-enabled: true`, `spring.kafka.listener.observation-enabled: true`; log pattern includes `traceId`/`spanId` (`logging.pattern.level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"`).
- [ ] Test config file with `management.tracing.enabled=false`.
- [ ] Compose: `jaeger` service `jaegertracing/all-in-one:1.62.0`, container `mutabik-jaeger`, ports `16686:16686`, `4318:4318`; payment-api env `MANAGEMENT_OTLP_TRACING_ENDPOINT`.
- [ ] Run `./mvnw -B test` → all green (92), no OTLP connection errors in output.
- [ ] Commit `feat(observability): distributed tracing via OpenTelemetry to Jaeger`.

### Task 2: Carry the trace through the outbox

**Files:** `OutboxJpaEntity.java` (+ `trace_parent VARCHAR(55)`), `OutboxPersistenceAdapter.java`, `KafkaTransferEventPublisher.java`, new `payment-infrastructure/.../messaging/TraceParent.java` (format/parse W3C `00-<32 hex>-<16 hex>-<2 hex>`), tests in `payment-infrastructure/src/test/...` using `io.micrometer:micrometer-tracing-test` (`SimpleTracer`).

**Interfaces:** `TraceParent.format(TraceContext ctx) -> String`, `TraceParent.parse(String) -> Optional<TraceContext>` (via `tracer.traceContextBuilder()`); adapter takes `io.micrometer.tracing.Tracer` (nullable-safe: no current span → null column). Publisher wraps each send in span `outbox.publish` whose parent is the parsed context (or a new root if absent/invalid) and sends inside `tracer.withSpan(span)` so Kafka's observation becomes a child.

- [ ] Tests: `storesTraceparentOfCurrentSpan`, `storesNullWithoutActiveSpan`, `publishContinuesStoredTrace` (published span's traceId equals stored), `malformedTraceparentStartsNewRoot`, existing publisher tests still pass.
- [ ] Implement; module tests green; full suite green.
- [ ] Commit `feat(observability): propagate trace context through the transactional outbox`.

### Task 3: Business metrics and alerts

**Files:** new `payment-api/.../observability/PaymentMetrics.java` (Spring component wrapping `MeterRegistry`), `MetricsConfig.java` (drop old counters), `TransferController`, `GatewayPaymentController`, `WebhookController` (or `GlobalPaymentExceptionHandler` for failure outcomes), `KafkaTransferEventPublisher` (failures), `KafkaTransferEventListener` (dlq, duplicates), outbox pending gauge (`SpringDataOutboxRepository.countByStatus("PENDING")`), `docker/prometheus/alerts.yml` (new) + `prometheus.yml` (`rule_files`) + compose volume, `docker/grafana/dashboards/payment-dashboard.json`.

**Interfaces:** `PaymentMetrics.transfer(String outcome)`, `.gatewayPayment(String outcome)`, `.webhook(String outcome)`; outcome mapping: `InsufficientFundsException`→insufficient_funds, `AccountNotFoundException`→not_found, `AccountInactiveException`/validation→rejected, idempotent replay→replay, other exception→error.

Alert rules (group `mutabik`):
`OutboxBacklogHigh` `mutabik_outbox_pending > 100` for 5m · `OutboxPublishFailing` `increase(mutabik_outbox_publish_failures_total[5m]) > 0` · `DeadLetterMessages` `increase(mutabik_kafka_dlq_messages_total[10m]) > 0` · `TransferErrorRateHigh` `sum(rate(mutabik_transfers_total{outcome="error"}[5m])) / clamp_min(sum(rate(mutabik_transfers_total[5m])), 1e-9) > 0.05` for 5m · `InvalidWebhookSignatureSpike` `increase(mutabik_webhooks_total{outcome="invalid_signature"}[5m]) > 10` · `TransferLatencyP95High` `histogram_quantile(0.95, sum by (le) (rate(payment_transfer_duration_seconds_bucket[5m]))) > 0.5` for 5m.

- [ ] Tests (`SimpleMeterRegistry` / MockMvc): success → `outcome=success` +1; insufficient funds → `insufficient_funds` (not `error`); replay → `replay`; invalid webhook signature → `webhooks{outcome=invalid_signature}`; publisher failure → `outbox.publish.failures` +1; DLQ listener → `kafka.dlq.messages` +1; duplicate → `kafka.duplicates.skipped` +1.
- [ ] Validate rules: `docker run --rm -v <abs>/docker/prometheus:/p prom/prometheus:v2.53.0 promtool check rules /p/alerts.yml` → SUCCESS.
- [ ] Dashboard panels for each new metric; JSON stays valid.
- [ ] Commit `feat(observability): business metrics, Grafana panels and Prometheus alerts`.

### Task 4: k6 load test

**Files:** `docker/keycloak/mutabik-realm.json` (client `mutabik-loadtest`: confidential, `directAccessGrantsEnabled: true`, `standardFlowEnabled: false`, secret `loadtest-local-only-secret`, same audience + customer_id mappers), `load/k6/transfers.js`, `tools/loadtest.ps1` (runs `grafana/k6:0.54.0` via docker with `host.docker.internal`).

- [ ] Script: `setup()` gets tokens for alice and bob (password grant, `client_id=mutabik-loadtest`); scenario `transfers` ramps to 20 VUs over ~2 min doing alternating alice→bob / bob→alice transfers of `0.01` with unique `Idempotency-Key`, plus `GET /api/v1/accounts`; thresholds per Global Constraints.
- [ ] Verify: realm re-import (`docker compose ... up -d --force-recreate keycloak`), run against the running stack → thresholds pass; record p95/p99, req/s, error rate; alice+bob total balance unchanged.
- [ ] Commit `feat(observability): k6 load test with local load-test client`.

### Task 5: Eclipse MAT heap-dump tooling

**Files:** `Dockerfile` (ENTRYPOINT adds `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/dumps`), compose volume `../dumps:/app/dumps` for payment-api, `run.ps1` (`-Dspring-boot.run.jvmArguments="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dumps"`), `tools/heapdump.ps1` (param `-Pid`, default: java process listening on 8080; `jcmd <pid> GC.heap_dump <abs>\dumps\heap-<yyyyMMdd-HHmmss>.hprof`), `tools/mat-report.ps1` (param `-Dump`; requires `$env:MAT_HOME`; runs `ParseHeapDump.bat <dump> org.eclipse.mat.api:suspects org.eclipse.mat.api:overview`; prints report zip paths), `.gitignore` (`dumps/`, `*.hprof`). Keep `run.ps1` UTF-8 with BOM.

- [ ] Verify `tools/heapdump.ps1` against the running app → `.hprof` file created, size printed. `mat-report.ps1` without `MAT_HOME` → clear error message (MAT itself not installed here; say so).
- [ ] Commit `feat(observability): heap-dump on OOM and Eclipse MAT report tooling`.

### Task 6: README and end-to-end verification

- [ ] README "Gözlemlenebilirlik" section: Jaeger URL and how to find a transfer's trace, metric list, alert table, k6 how-to + measured results, MAT how-to (dominator tree, retained heap, Leak Suspects) and the known candidate (`RedisIdempotencyAdapter.localMemoryCache` grows unbounded).
- [ ] Live: one transfer → Jaeger shows a single trace containing HTTP, JDBC, `outbox.publish`, Kafka send and Kafka receive spans; Prometheus `/rules` lists the 6 alerts; Grafana panels show data.
- [ ] `./mvnw -B test` green; commit `docs(readme): observability, load test and heap analysis`.
