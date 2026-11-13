# Keycloak Authentication & Account Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Protect the Mutabık API with Keycloak-issued JWTs, CUSTOMER/ADMIN roles and account-ownership checks, and remove the reset / concurrency-simulation / webhook-simulate demo tools.

**Architecture:** Keycloak (compose service, port 8180) authenticates users; `payment-api` is an OAuth2 resource server that turns a validated JWT into a Spring-free `Requester` value. Every ownership rule lives in `AccountAccessPolicy` in `payment-application`; use cases and queries take a `Requester` and call the policy. The console logs in with `keycloak-js` (Authorization Code + PKCE) and sends `Authorization: Bearer` on every call.

**Tech Stack:** Java 21, Spring Boot 3.3.5, spring-boot-starter-oauth2-resource-server, spring-security-test, Keycloak (quay.io/keycloak/keycloak), keycloak-js (CDN, pinned), JUnit 5, Mockito, AssertJ, H2 for tests.

**Spec:** `docs/superpowers/specs/2026-11-13-authentication-design.md`

## Global Constraints

- `payment-domain` and `payment-application` must not depend on Spring (no new Spring imports there).
- Tests must not need Docker or Keycloak; use `spring-security-test` `jwt()`.
- Roles: realm roles `CUSTOMER`, `ADMIN` in `realm_access.roles` → authorities `ROLE_CUSTOMER`, `ROLE_ADMIN`.
- Token claims: `customer_id` (e.g. `CUST-ALICE`), `aud` contains `mutabik-api`, `iss` = `http://localhost:8180/realms/mutabik`.
- Status codes: no/invalid token → 401; wrong role → 403; account not owned or not existing → 404 (indistinguishable). Errors are RFC 7807 `application/problem+json`.
- ADMIN never initiates transfers or gateway payments (403). No security bypass / dev mode.
- Seed owners: Alice `11111111-…` → `CUST-ALICE`, Bob `22222222-…` → `CUST-BOB`, Charlie `33333333-…` → `CUST-CHARLIE`, Clearing `99999999-…` → `null` (bank-internal).
- Removed endpoints: `POST /api/v1/accounts/reset`, `POST /api/v1/simulation/concurrency`, `POST /api/v1/webhooks/gateway/simulate`.
- Management/actuator on port `8081` (`management.server.port: 8081`), not published by compose.
- Webhook secret from `payment.gateway.webhook-secret` (env `PAYMENT_GATEWAY_WEBHOOK_SECRET`); the in-code default is for local development only.
- Build/test command prefix (Git Bash): `export JAVA_HOME="/c/Program Files/BellSoft/LibericaJDK-21" PATH="/c/Program Files/BellSoft/LibericaJDK-21/bin:$PATH"; ./mvnw -B`.

## Review Focus

- A CUSTOMER token **without** a `customer_id` claim must be rejected (401). It must never be turned into a requester whose `null` customer id "owns" the bank-internal Clearing account (owner `null`). → test in Task 2 (policy) and Task 6 (HTTP).
- A token signed correctly but with the wrong `aud` or `iss` (e.g. issued via `http://keycloak:8080`) must get 401. → Task 6.
- A customer sending money **to** the internal Clearing account (no owner) gets 404, the same as a non-existent target. → Task 3.
- The same `Idempotency-Key` used by two different customers must not replay one customer's cached result to the other, while a retry by the same customer still replays. → Task 3.
- The HMAC webhook `POST /api/v1/webhooks/gateway` must stay reachable with no token after the security chain is added. → Task 6.

---

### Task 1: Account ownership in the domain and persistence

**Files:**
- Modify: `payment-domain/src/main/java/com/bank/payment/domain/model/account/Account.java`
- Create: `payment-domain/src/main/java/com/bank/payment/domain/exception/AccountNotFoundException.java`
- Modify: `payment-infrastructure/src/main/java/com/bank/payment/infrastructure/persistence/entity/AccountJpaEntity.java`
- Modify: `payment-api/src/main/java/com/bank/payment/api/config/DataLoader.java`
- Modify (constructor call sites): every `new Account(` in main and test code (`grep -rn "new Account(" --include=*.java`)
- Test: `payment-domain/src/test/java/com/bank/payment/domain/AccountDomainTest.java`

**Interfaces:**
- Produces:
  - `Account(AccountId id, String customerNumber, Money balance, AccountStatus status, Long version, String ownerCustomerId)`; `String getOwnerCustomerId()` (nullable); `boolean isOwnedBy(String customerId)` → `customerId != null && customerId.equals(ownerCustomerId)`.
  - `AccountNotFoundException(AccountId id)` extends `RuntimeException`; message `"Hesap bulunamadı: " + id`.
  - Column `accounts.owner_customer_id VARCHAR(64) NULL`.

- [ ] **Step 1: Write failing tests** in `AccountDomainTest`:
  - `isOwnedByMatchesOwner`: account owned by `"CUST-ALICE"` → `isOwnedBy("CUST-ALICE")` true, `isOwnedBy("CUST-BOB")` false.
  - `internalAccountIsOwnedByNobody`: owner `null` → `isOwnedBy(null)` false, `isOwnedBy("CUST-ALICE")` false.
- [ ] **Step 2: Run** `./mvnw -B -q test -pl payment-domain` → compilation failure (no 6-arg constructor).
- [ ] **Step 3: Implement** the constructor, getter and `isOwnedBy`; add `AccountNotFoundException`. Update all `new Account(` call sites (tests pass `"CUST-1"`/`"CUST-2"` or `null` as appropriate).
- [ ] **Step 4: Persistence + seed.** Add `ownerCustomerId` to `AccountJpaEntity` (`@Column(name = "owner_customer_id", length = 64)`, mapped in `toDomain`/`fromDomain`). In `DataLoader`, seed owners per Global Constraints; when accounts already exist, set `ownerCustomerId` on the four seeded IDs whose owner is still `null` and whose seed owner is non-null (demo backfill, idempotent).
- [ ] **Step 5: Run** `./mvnw -B -q test` → all existing tests and the 2 new ones pass.
- [ ] **Step 6: Commit** `feat(domain): add account ownership`.

### Task 2: Requester and AccountAccessPolicy (application layer)

**Files:**
- Create: `payment-application/src/main/java/com/bank/payment/application/security/Requester.java`
- Create: `payment-application/src/main/java/com/bank/payment/application/security/AccountAccessPolicy.java`
- Test: `payment-application/src/test/java/com/bank/payment/application/AccountAccessPolicyTest.java`

**Interfaces:**
- Produces:
  - `record Requester(Kind kind, String customerId)`, `enum Kind { CUSTOMER, ADMIN, SYSTEM }`. Factories `customer(String customerId)` (throws `IllegalArgumentException` if null/blank), `admin()`, `system()`. `boolean isCustomer()/isAdmin()/isSystem()`. `IdempotencyKey scope(IdempotencyKey raw)` → value `"<customerId>:<raw>"` for CUSTOMER, `"admin:<raw>"`, `"system:<raw>"`.
  - `final class AccountAccessPolicy` (no Spring): `void assertCanMoveFundsFrom(Requester r, Account a)`, `void assertCanReceive(Requester r, Account a)`, `void assertCanView(Requester r, Account a)`, `boolean canView(Requester r, Account a)`. Violations throw `AccountNotFoundException(a.getId())`.
  - Rules: move-from — CUSTOMER owner or SYSTEM; ADMIN never. Receive — SYSTEM always; CUSTOMER only if the target has an owner (not internal); ADMIN never. View — owner CUSTOMER or ADMIN or SYSTEM.

- [ ] **Step 1: Write failing tests** (`AccountAccessPolicyTest`), each asserting allow or `AccountNotFoundException`:
  - `customerCanMoveFundsFromOwnAccount`, `customerCannotMoveFundsFromOthersAccount`, `adminCannotMoveFunds`, `systemCanMoveFunds`.
  - `customerCanSendToOtherCustomer`, `customerCannotSendToInternalAccount` (target owner `null`).
  - `customerCanViewOnlyOwnAccount`, `adminCanViewAnyAccount`.
  - `requesterCustomerRequiresId`: `Requester.customer(null)` and `Requester.customer(" ")` throw `IllegalArgumentException`.
  - `scopeNamespacesKeyPerRequester`: `customer("CUST-ALICE").scope(of("k1"))` = `"CUST-ALICE:k1"`; differs from `customer("CUST-BOB").scope(of("k1"))`.
- [ ] **Step 2: Run** `./mvnw -B -q test -pl payment-application -am -Dtest=AccountAccessPolicyTest -Dsurefire.failIfNoSpecifiedTests=false` → compilation failure.
- [ ] **Step 3: Implement** `Requester` and `AccountAccessPolicy`.
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** `feat(application): add Requester and AccountAccessPolicy`.

### Task 3: Ownership and per-user idempotency in transfers

**Files:**
- Modify: `payment-application/src/main/java/com/bank/payment/application/dto/InitiateTransferCommand.java`
- Modify: `payment-application/src/main/java/com/bank/payment/application/service/TransferExecutionService.java`
- Modify: `payment-infrastructure/src/main/java/com/bank/payment/infrastructure/persistence/entity/TransferJpaEntity.java` (`idempotency_key` length 120 → 200)
- Modify: `payment-api/src/main/java/com/bank/payment/api/config/BeanConfig.java` (construct with `new AccountAccessPolicy()`)
- Test: `payment-application/src/test/java/com/bank/payment/application/TransferExecutionServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `Requester`, `AccountAccessPolicy`; Task 1 `AccountNotFoundException`.
- Produces: `InitiateTransferCommand(IdempotencyKey idempotencyKey, AccountId sourceAccountId, AccountId targetAccountId, Money amount, String description, Requester requester)` (requester non-null). `TransferExecutionService(…existing ports…, TransactionPort transactionPort, AccountAccessPolicy accessPolicy)`.
- Behavior: cache lookup, in-flight lock and the persisted `Transfer.idempotencyKey` use `requester.scope(key)`; `TransferResultDto.idempotencyKey` returns the raw key. Missing accounts throw `AccountNotFoundException`. After both accounts are locked: `assertCanMoveFundsFrom(requester, source)`, `assertCanReceive(requester, target)`.

- [ ] **Step 1: Update existing tests** to pass `Requester.customer("CUST-1")` and owner `"CUST-1"` on the source account; add failing tests:
  - `shouldRejectTransferFromAccountNotOwned`: requester `CUST-2`, source owned by `CUST-1` → `AccountNotFoundException`; `updateAccount`, `saveTransfer`, `cacheResult` never called.
  - `shouldRejectTransferToInternalAccount`: target owner `null` → `AccountNotFoundException`.
  - `shouldScopeIdempotencyKeyPerRequester`: verify `getCachedResult`, `acquireLock`, `cacheResult` receive `IdempotencyKey.of("CUST-1:IDEMP-KEY-1")`; result `idempotencyKey()` equals `"IDEMP-KEY-1"`.
  - `missingAccountThrowsAccountNotFound`: `loadAccountWithLock` empty → `AccountNotFoundException`.
- [ ] **Step 2: Run** `./mvnw -B -q test -pl payment-application -am -Dtest=TransferExecutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 3: Implement** the command field, scoping and policy checks; widen the column.
- [ ] **Step 4: Run** → PASS. Note: from this task until Task 6, `payment-api` does not compile (controllers still build commands without a `Requester`). That is expected; run only module-scoped tests in Tasks 3–5. Do **not** bridge the gap with `Requester.system()` in controllers.
- [ ] **Step 5: Commit** `feat(transfers): enforce account ownership and per-user idempotency`.

### Task 4: Ownership in gateway payments

**Files:**
- Modify: `payment-application/src/main/java/com/bank/payment/application/port/in/InitiateGatewayPaymentUseCase.java`
- Modify: `payment-application/src/main/java/com/bank/payment/application/service/GatewayPaymentWorkflowService.java`
- Test: `payment-application/src/test/java/com/bank/payment/application/GatewayPaymentWorkflowServiceTest.java`

**Interfaces:**
- Consumes: Task 2.
- Produces: `GatewayPaymentCommand(..., String description, Requester requester)` (new last field). `GatewayPaymentWorkflowService(..., PaymentGatewayPort paymentGatewayPort, AccountAccessPolicy accessPolicy)`. `initiatePayment` loads both accounts with `loadAccountPort.loadAccount` (no lock) before calling the gateway and applies `assertCanMoveFundsFrom(requester, customerAccount)` and `assertCanReceive(requester, merchantAccount)`; lock key = `requester.scope(key)`. `processWebhook` is unchanged (HMAC-verified, acts as system).

- [ ] **Step 1: Write failing tests**: `shouldRejectPaymentFromAccountNotOwned` (→ `AccountNotFoundException`, `authorizePayment` never called), `shouldAuthorizePaymentFromOwnAccount` (status `AUTHORIZED`).
- [ ] **Step 2: Run** `-Dtest=GatewayPaymentWorkflowServiceTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(gateway): enforce account ownership on payments`.

### Task 5: Requester-aware read queries (accounts, statement, transfers, recipients)

**Files:**
- Modify: `payment-application/src/main/java/com/bank/payment/application/port/in/GetAccountBalanceQuery.java`, `GetAccountStatementQuery.java`
- Create: `payment-application/src/main/java/com/bank/payment/application/port/in/GetRecentTransfersQuery.java`, `GetRecipientsQuery.java`
- Create: `payment-application/src/main/java/com/bank/payment/application/dto/TransferSummaryDto.java`, `RecipientDto.java`
- Create: `payment-application/src/main/java/com/bank/payment/application/port/out/LoadLedgerPort.java`, `LoadTransfersPort.java`
- Create: `payment-application/src/main/java/com/bank/payment/application/service/AccountQueryService.java`
- Modify: `TransferExecutionService.java` (remove the query interfaces it implements today)
- Create/Modify infra adapters: `LedgerQueryAdapter.java` (new, `persistence/adapter`), `TransferPersistenceAdapter.java`, `SpringDataTransferRepository.java`
- Modify: `BeanConfig.java` (bean `AccountQueryService`)
- Test: `payment-application/src/test/java/com/bank/payment/application/AccountQueryServiceTest.java`

**Interfaces:**
- Consumes: Task 1, Task 2.
- Produces:
  - `GetAccountBalanceQuery`: `List<AccountBalanceDto> getAccounts(Requester r)` (CUSTOMER → owned only; ADMIN/SYSTEM → all), `AccountBalanceDto getAccount(Requester r, AccountId id)`.
  - `GetAccountStatementQuery`: `AccountStatementDto getStatement(Requester r, AccountId id)` with real ledger items, newest first.
  - `GetRecentTransfersQuery`: `List<TransferSummaryDto> getRecentTransfers(Requester r)` — 50 newest; CUSTOMER → only where source or target is an owned account.
  - `GetRecipientsQuery`: `List<RecipientDto> getRecipients(Requester r)` — owned (`ownerCustomerId != null`), `ACTIVE`, not owned by `r`.
  - `record TransferSummaryDto(UUID transferId, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount, String currency, TransferStatus status, PaymentGatewayType gatewayType, Instant createdAt)`.
  - `record RecipientDto(UUID accountId, String displayName, String maskedIban)`.
  - `LoadLedgerPort.loadEntries(AccountId id)` → `List<AccountStatementDto.LedgerItem>`; `LoadTransfersPort.loadRecent(int limit)` and `loadRecentInvolving(Collection<AccountId> ids, int limit)` → `List<TransferSummaryDto>`.
  - `AccountQueryService implements GetAccountBalanceQuery, GetAccountStatementQuery, GetRecentTransfersQuery, GetRecipientsQuery`.
- Masking: from `customerNumber` `"TR330006100511123456780002 (Bob Jones)"` → `displayName` `"Bob J."`, `maskedIban` `"TR33 **** 0002"` (first 4 + `" **** "` + last 4 of the IBAN part).

- [ ] **Step 1: Write failing tests** (`AccountQueryServiceTest`, ports mocked):
  - `customerSeesOnlyOwnAccounts`, `adminSeesAllAccounts`.
  - `customerCannotReadOthersAccountOrStatement` → `AccountNotFoundException` for both `getAccount` and `getStatement`.
  - `customerRecentTransfersOnlyInvolvingOwnAccounts`: verifies `loadRecentInvolving([ownId], 50)` used, `loadRecent` not.
  - `recipientsExcludeOwnInternalAndInactive`: Alice sees Bob and Charlie only; Bob entry equals `RecipientDto(bobId, "Bob J.", "TR33 **** 0002")`.
- [ ] **Step 2: Run** `-Dtest=AccountQueryServiceTest` → FAIL.
- [ ] **Step 3: Implement** service, ports and adapters.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(queries): requester-aware account, statement, transfer and recipient queries`.

### Task 6: Resource-server security, controllers and demo-tool removal

**Files:**
- Modify: `payment-api/pom.xml` (add `spring-boot-starter-oauth2-resource-server`; test `spring-security-test`)
- Create: `payment-api/src/main/java/com/bank/payment/api/security/SecurityConfig.java`, `KeycloakRoleConverter.java`, `RequesterResolver.java`, `ProblemAuthenticationEntryPoint.java`, `ProblemAccessDeniedHandler.java`
- Modify: `payment-api/src/main/resources/application.yml` (security + `management.server.port: 8081`), `application-local.yml` if needed
- Modify: `AccountController.java`, `TransferController.java`, `GatewayPaymentController.java`, `WebhookController.java`, `GlobalPaymentExceptionHandler.java`
- Delete: `SimulationController.java`; `AccountController.resetBalances`; `WebhookController.simulateWebhook`
- Modify: `docker/prometheus/prometheus.yml` (targets `payment-api:8081`, `host.docker.internal:8081`)
- Test: `payment-api/src/test/java/com/bank/payment/api/PaymentCoreIntegrationTest.java`, create `SecurityIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 3–5 use cases and queries.
- Produces:
  - Properties: `mutabik.security.issuer` (default `http://localhost:8180/realms/mutabik`), `mutabik.security.jwk-set-uri` (default `${mutabik.security.issuer}/protocol/openid-connect/certs`), `mutabik.security.audience` (default `mutabik-api`). Decoder: `NimbusJwtDecoder.withJwkSetUri(...)` + validators for issuer, audience, timestamps.
  - `RequesterResolver.resolve(Authentication auth)` → `Requester`: `ROLE_ADMIN` → `admin()`; `ROLE_CUSTOMER` with non-blank `customer_id` → `customer(id)`; otherwise throws `InvalidBearerTokenException` (→ 401).
  - Route rules exactly per the spec's access matrix; `CUSTOMER` only for `POST /api/v1/transfers` and `POST /api/v1/gateway/pay`; `ADMIN` for `/api/v1/outbox/**`; authenticated for other `/api/v1/**`; `permitAll` for static files and `POST /api/v1/webhooks/gateway`. Stateless, CSRF disabled.
  - New endpoint `GET /api/v1/accounts/recipients`; `GET /api/v1/transfers` returns `List<TransferSummaryDto>`.
  - `AccountNotFoundException` → 404 problem, title `"Account Not Found"`.
- Note: `IdempotencyFilter` keeps working on the raw header; no change.

- [ ] **Step 1: Write failing tests** in `SecurityIntegrationTest` (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("local")`, token helpers `alice()`, `bob()`, `admin()` built with `jwt().jwt(j -> j.claim("customer_id", …)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))`):
  - `noTokenIs401WithProblemJson` (`GET /api/v1/accounts`).
  - `customerTokenWithoutCustomerIdIs401`.
  - `wrongAudienceOrIssuerIs401`: unit test of the decoder's validator chain with a `Jwt` whose `aud` is `["other"]` and one whose `iss` is `http://keycloak:8080/realms/mutabik` → validation fails.
  - `aliceCannotTransferFromBobsAccount` → 404; `aliceCannotReadBobsStatement` → 404; `unknownAccountIs404` → 404 (same title as the previous two).
  - `aliceListsOnlyHerAccount` (`$.length()` = 1, id `11111111-…`); `adminListsAllAccounts` (4).
  - `aliceCannotSendToClearing` → 404.
  - `customerCannotUseOutbox` → 403; `adminCannotTransfer` → 403; `adminCannotPay` → 403.
  - `removedDemoEndpointsAre404` (as admin): the three removed paths.
  - `webhookWorksWithoutToken`: authorize a payment as alice, then POST a correctly HMAC-signed payload to `/api/v1/webhooks/gateway` with no token → 200, `$.status` = `SETTLED`.
  - `sameIdempotencyKeyDoesNotLeakAcrossUsers`: alice transfers with key `K` → 200 `idempotentReplay=false`; bob transfers from his own account with key `K` → 200 `idempotentReplay=false` and his own `sourceAccountId`; alice repeats `K` → `idempotentReplay=true`.
- [ ] **Step 2: Update `PaymentCoreIntegrationTest`**: add `.with(alice())` / `.with(admin())` to every request; `testPaymentGatewayAndWebhookFlow` signs the webhook with `MockPaymentGatewayAdapter.computeHmacSha256` and posts to `/api/v1/webhooks/gateway`; `testGetAccounts` uses `admin()` and still expects 4.
- [ ] **Step 3: Run** `./mvnw -B -q test -pl payment-api -am` → FAIL (no security yet / compilation).
- [ ] **Step 4: Implement** security config, resolver, controllers, exception mapping, deletions, ports.
- [ ] **Step 5: Run** `./mvnw -B test` → all modules PASS (25 existing tests adjusted + new ones).
- [ ] **Step 6: Commit** `feat(api): Keycloak resource server, role/ownership enforcement, remove demo tools`.

### Task 7: Keycloak realm, compose, scripts and README

**Files:**
- Create: `docker/keycloak/mutabik-realm.json`
- Modify: `docker/docker-compose.yml`, `run.ps1` (keep UTF-8 BOM), `README.md`

**Interfaces:**
- Consumes: claim/role/client contract from Global Constraints.
- Produces:
  - Compose service `keycloak`: image `quay.io/keycloak/keycloak:25.0.6`, `command: start-dev --import-realm`, port `8180:8080`, env `KEYCLOAK_ADMIN=admin`, `KEYCLOAK_ADMIN_PASSWORD=admin` (master-realm console only; separate from the `mutabik` realm's `admin` user), `KC_HOSTNAME=http://localhost:8180`, `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, volume `./keycloak:/opt/keycloak/data/import:ro`, container `mutabik-keycloak`.
  - `payment-api` service env: `MUTABIK_SECURITY_JWK_SET_URI=http://keycloak:8080/realms/mutabik/protocol/openid-connect/certs`, `PAYMENT_GATEWAY_WEBHOOK_SECRET`; `depends_on: keycloak`. Do not publish 8081.
  - Realm `mutabik`: roles `CUSTOMER`, `ADMIN`; public client `mutabik-console` (standard flow, PKCE S256, redirect `http://localhost:8080/*`, web origin `http://localhost:8080`); mappers: user attribute `customer_id` → claim `customer_id` (access token), audience `mutabik-api`; user profile makes `customer_id` admin-edit-only; users `alice`/`bob`/`charlie` (CUSTOMER, `customer_id` `CUST-ALICE`/`CUST-BOB`/`CUST-CHARLIE`) and `admin` (ADMIN, no `customer_id`); demo passwords `alice123`, `bob123`, `charlie123`, `admin123`.
  - `run.ps1 -Docker` starts keycloak too; `-Local` starts only `keycloak` via compose before running the app.
  - README: auth section (diagram, access matrix, demo users/passwords, Keycloak URL), signed-webhook `curl` + `openssl` example, "reset = `docker compose down -v`", concurrency claims point to the tests, interview points (IDOR/BOLA, authn at IdP / authz in app); remove reset/simulation/simulate references.

- [ ] **Step 1: Verify realm** — `docker compose -f docker/docker-compose.yml up -d keycloak`; wait for `http://localhost:8180/realms/mutabik/.well-known/openid-configuration` → 200 and `issuer` = `http://localhost:8180/realms/mutabik`.
- [ ] **Step 2: Verify token claims** — obtain a token for alice via the browser login (or temporarily enable direct access grants **only in a scratch copy**, never in the committed realm) and decode it: `customer_id` = `CUST-ALICE`, `aud` contains `mutabik-api`, `realm_access.roles` contains `CUSTOMER`.
- [ ] **Step 3: Verify** `pwsh`/PowerShell 5.1 parses `run.ps1` (BOM intact) and `-Docker` brings up keycloak with the others.
- [ ] **Step 4: Commit** `feat(infra): Keycloak realm and compose service; document authentication`.

### Task 8: Console login and role-aware views

**Files:**
- Modify: `payment-api/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: Task 6 endpoints, Task 7 realm/client.
- Produces: `keycloak-js@25.0.6` from `https://cdn.jsdelivr.net/npm/keycloak-js@25.0.6/dist/keycloak.min.js`; init `onLoad: 'login-required', pkceMethod: 'S256'`, url `http://localhost:8180`, realm `mutabik`, clientId `mutabik-console`. One helper `async function api(path, options)` used by every `fetch`: `updateToken(30)`, adds `Authorization: Bearer`, on 401 calls `keycloak.login()`.

- [ ] **Step 1: Implement**:
  - Header: username, role badge, "Çıkış" (`keycloak.logout()`).
  - Customer: source = own account (fixed), target dropdown from `/api/v1/accounts/recipients`; gateway panel keeps only the "pay" step, shows `AUTHORIZED` as "ödeme sağlayıcısının onayı bekleniyor"; outbox panel hidden.
  - Admin: all accounts, all transfers, outbox panel; transfer and gateway forms hidden.
  - Delete: reset button + `resetBalances()`, the concurrency simulator card + `runSimulation/setSimThreads/updateSimBtnText`, `runGatewayWebhook/runAutomatedGatewayFlow` and their buttons.
  - Ledger dropdown: customer → own account; admin → all.
- [ ] **Step 2: Verify in a browser** with compose up (postgres, redis, kafka, keycloak) and the app running: log in as alice → transfer to Bob succeeds, Bob's statement not selectable, outbox hidden; log in as admin → 4 accounts, outbox visible, no transfer form; browser console has no errors.
- [ ] **Step 3: Commit** `feat(console): Keycloak login and role-aware views`.

### Task 9: End-to-end verification

- [ ] **Step 1: Run** `./mvnw -B test` → BUILD SUCCESS, 0 failures.
- [ ] **Step 2: Run** the integration tests against Postgres (env `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/settlement_db`, `SPRING_DATASOURCE_USERNAME/PASSWORD=postgres`, `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver`) → PASS.
- [ ] **Step 3: Live check** with a real Keycloak token (from the console session): `curl` as alice against Bob's statement → 404; as admin `POST /api/v1/transfers` → 403; no token → 401; signed webhook with no token → 200; Prometheus target `:8081` is `UP` at `http://localhost:9090/targets` when the API runs in compose.
- [ ] **Step 4: Stop** app and containers; report results.
