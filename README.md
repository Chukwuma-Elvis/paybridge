# PayBridge

[![Java](https://img.shields.io/badge/Java-25-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring Modulith](https://img.shields.io/badge/Spring%20Modulith-2.1.1-6DB33F?style=for-the-badge)](https://spring.io/projects/spring-modulith)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-336791?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Maven](https://img.shields.io/badge/Maven-Wrapper-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Tests](https://img.shields.io/badge/Tests-488%20passing-brightgreen?style=for-the-badge)](#-testing--verification)

> **A Core Banking & Settlement Engine engineered as a Spring Boot Modular Monolith.**
> Built to model real-world financial infrastructure: double-entry bookkeeping, zero-overdraft concurrency locking, strict idempotency, an asynchronous outbox gateway dispatcher with reconciliation, and tiered compliance limits.

**Status (2026-10-14):** Phase 1, local NGN-to-NGN transfers, is built and now **secured with API keys and rate limits**. Internal transfers are complete; external transfers run end to end against a **simulated bank**. **Payment-provider webhooks** (Paystack and Monnify format) are built and tested, so a transfer can now be finished, refunded or reversed by the provider telling us. The real provider *clients* (initiating transfers with Paystack or Monnify) are **not built yet**. See [Project Status](#-project-status) for the honest list, [`STEP-12-GUIDE.md`](STEP-12-GUIDE.md) and [`STEP-13-GUIDE.md`](STEP-13-GUIDE.md) for line-by-line explanations, [`plans/fx-remittance-settlement.plan.md`](plans/fx-remittance-settlement.plan.md) for the plan for the next phase, and [`BUILD-LOG.md`](BUILD-LOG.md) for every decision and the reasoning behind it.

---

## Table of Contents

1. [Architectural Highlights](#-architectural-highlights)
2. [Module Architecture](#-module-architecture)
3. [Transfer Workflows & Flowcharts](#-transfer-workflows--flowcharts)
   - [1. Internal Account-to-Account Transfer](#1-internal-account-to-account-transfer)
   - [2. External Transfer with Simulated Gateway & Reconciler](#2-external-transfer-with-simulated-gateway--reconciler)
4. [External Bank Simulation (Deterministic Behavioral Profiles)](#-external-bank-simulation-profiles)
5. [API Specification](#-api-specification)
6. [Observability & Request Logging](#-observability--request-logging)
7. [Configuration](#-configuration)
8. [Getting Started](#-getting-started)
9. [Testing & Verification](#-testing--verification)
10. [Project Status](#-project-status)
11. [Further Reading](#-further-reading)

---

## 🏛️ Architectural Highlights

PayBridge is architected around foundational financial engineering principles:

* **Append-Only Double-Entry Bookkeeping:** Money is never created or destroyed out of thin air. Every movement is a balanced journal entry where debits and credits sum to zero ($\sum \text{lines} = 0$). PostgreSQL triggers enforce that `journal_entry` and `ledger_line` records can **never** be edited or deleted (`UPDATE`/`DELETE` prohibited), and a deferred constraint trigger rejects an unbalanced entry at commit. Mistakes are fixed with a reversing entry, never an eraser.
* **Integer Kobo Arithmetic:** Floating-point numbers are not used in any money pathway. Values are 64-bit signed integers in minor units (kobo: $100\text{ kobo} = ₦1.00$), held in an immutable `Money` value object that refuses to mix currencies and throws on overflow.
* **Pessimistic Row Locking in Canonical Order:** Simultaneous transfers acquire pessimistic write locks (`SELECT ... FOR UPDATE`) sorted strictly by account id, which removes the classic opposite-direction deadlock. An `@Version` column is a second safety net.
* **Database Check Constraints as a Second Line of Defense:** Even if the code-level validation failed, the schema rejects a negative customer balance through the check constraint `ck_account_customer_not_negative`.
* **Request Fingerprinting & Idempotency:** The client sends an `Idempotency-Key` header. PayBridge claims it with `INSERT ... ON CONFLICT DO NOTHING` and stores a SHA-256 fingerprint of the request. A duplicate request returns `HTTP 200 OK` with `Idempotent-Replayed: true` and the original receipt, without moving funds twice. Reusing a key with a different body is refused (`422`). A refused attempt rolls back entirely, so its key is not burned.
* **The Three Rules of External Settlement:**
  1. *Never call an external banking API inside a database transaction* (it would hold locks and risk paying without a record, or recording without paying).
  2. *A gateway timeout means "unknown", never "failed"* (the funds stay safely held in a `SUSPENSE` account while the system finds out).
  3. *Applying a bank's answer must be idempotent* (a repeated poll or webhook can never pay out twice).
* **Spring Modulith Boundaries:** Modules interact only through their public `api/` packages. A cross-module leak fails the build's tests (`ModularityTests`).
* **API-Key Security, Hashed at Rest:** every endpoint except the health check needs an `X-API-Key` header. Keys are 256 bits of randomness and only their SHA-256 hash is stored, so a copy of the database cannot be used to call the API. Keys have roles (`CLIENT`, `ADMIN`); money-creating and key-management endpoints are admin-only. The first admin key is supplied through an environment variable, never a file.
* **Rate Limiting & Guess Protection:** each key has a token-bucket budget (and a tighter one for submitting transfers). An address that presents too many invalid keys is blocked, even for its next, correct guess.
* **Events After Commit:** Customer alerts are delivered through Spring Modulith events (`@ApplicationModuleListener`): they run after the transaction commits, asynchronously, and are persisted until handled. A failed alert can never undo a transfer.

---

## 🧩 Module Architecture

PayBridge avoids distributed-microservice overhead by enforcing hard module boundaries inside a single deployable application:

```
src/main/java/com/academy/paybridge/
├── customer/      # Onboarding, phone normalisation (+234), KYC verification tiers
├── account/       # Account lifecycle, check-digit NUBAN generation (bank code 999), system accounts
├── ledger/        # Append-only journal entries and lines, statements, dev-only funding
├── transfer/      # Internal transfers, external outbox + dispatcher + reconciler, idempotency, payees
│   └── client/    #   TransferGateway interface + FakeTransferGateway (the simulated bank)
├── compliance/    # Atomic per-tier daily and per-transaction limit reservations
├── notification/  # Asynchronous SMS and email alerts delivered through domain events
├── security/      # API keys (hashed), roles, authentication + rate-limit filters, key management endpoints
├── shared/        # Immutable Money, error model, auditing, request-id and access-log filters, log correlation
└── [fx / remittance / settlement]   # Planned later phases, not started
```

Each module is split into layers: `api/` (the public front desk), `domain/`, `repository/`, `service/` and `web/`.

### Module Responsibilities

| Module | Responsibility | Public Interface | Persistence |
|---|---|---|---|
| **`customer`** | Customer identity, phone normalisation, KYC tiers | `CustomerApi` | `customer` |
| **`account`** | Customer and system accounts (`FUNDING_SOURCE`, `SUSPENSE`, `PAYSTACK_SETTLEMENT`, `FEES`), cached balances | `AccountApi` | `account`, `account_number_seq` |
| **`ledger`** | Balanced double-entry postings, reversals, statements | `LedgerApi` | `journal_entry`, `ledger_line` |
| **`transfer`** | Internal transfers, external outbox, payees, idempotency | `TransferApi` | `transfer`, `idempotency_key`, `beneficiary`, `transfer_outbox` |
| **`compliance`** | Tiered daily and per-transaction quota reservations | `ComplianceApi` | `daily_usage` |
| **`notification`** | Customer alerts, decoupled through Modulith events | `@ApplicationModuleListener` | `notification` |
| **`security`** | API keys, roles, key-checking and rate-limiting filters, key management | `/api/admin/api-keys` (nothing else depends on this module) | `api_key` |
| **`shared`** | `Money` value object, error codes, auditing, request correlation | Open module (used by all) | `event_publication` |

The database schema is versioned with Flyway migrations `V1` to `V10` (`src/main/resources/db/migration`), and Hibernate runs in `validate` mode: it checks the tables but never changes them.

---

## 🔄 Transfer Workflows & Flowcharts

### 1. Internal Account-to-Account Transfer

An internal transfer moves funds between two PayBridge accounts inside a **single atomic database transaction**.

#### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client App
    participant Web as TransferController
    participant Svc as TransferService
    participant Idem as IdempotencyService
    participant Acc as AccountService
    participant Comp as ComplianceService
    participant Ledg as LedgerService
    participant DB as PostgreSQL Database
    participant Event as Modulith Event Bus
    participant Notif as NotificationListener

    Client->>Web: POST /api/transfers/internal<br/>[Header: Idempotency-Key: demo-001]<br/>{sourceAccountId, destinationAccountNumber, amountMinor, narration}
    Note over Web: RequestIdFilter stamps X-Request-Id
    Web->>Svc: transferInternal(command, idempotencyKey)

    activate Svc
    Note over Svc: BEGIN ATOMIC TRANSACTION
    Svc->>Idem: begin(key, payloadFingerprint)
    Idem->>DB: INSERT INTO idempotency_key ON CONFLICT DO NOTHING

    alt Idempotency Key Already Exists
        DB-->>Idem: 0 rows inserted (key exists)
        Idem-->>Svc: Returns existing transferId (replay)
        Svc-->>Web: TransferResult(transfer, replayed=true)
        Web-->>Client: HTTP 200 OK [Idempotent-Replayed: true]
    else New Request (Claimed)
        DB-->>Idem: 1 row inserted (claim acquired)
        Svc->>Acc: Verify source and destination accounts
        Acc-->>Svc: Accounts active, currency matches

        Svc->>Comp: reserve(senderId, amountMinor)
        Comp->>DB: Atomic UPSERT into daily_usage while within limit
        DB-->>Comp: Quota reserved

        Svc->>DB: INSERT INTO transfer (status=PENDING, ref=pb-uuid)

        Svc->>Ledg: post(sourceId, destId, amountMinor)
        activate Ledg
        Ledg->>DB: SELECT ... FROM account WHERE id IN (src, dst) FOR UPDATE (sorted order)
        Note over DB: Row locks acquired without deadlocks
        Ledg->>Ledg: Check every rule before changing anything
        Ledg->>DB: UPDATE account balance_minor (source -500, destination +500)
        Ledg->>DB: INSERT INTO journal_entry
        Ledg->>DB: INSERT INTO ledger_line (source: -500, destination: +500)
        deactivate Ledg

        Svc->>DB: UPDATE transfer SET status = SUCCESS
        Svc->>Idem: complete(key, transferId)
        Svc->>Event: publishEvent(TransferCompleted)

        Note over DB: COMMIT TRANSACTION<br/>(trigger verifies sum of ledger lines = 0)
        deactivate Svc

        Web-->>Client: HTTP 201 Created [Location: /api/transfers/:id]

        par Async post-commit notification
            Event->>Notif: deliver(TransferCompleted)
            Notif->>DB: INSERT INTO notification (SMS, email)
            Notif->>Notif: Send alerts
        end
    end
```

A refused request (overdraw, over the limit, missing key, key reuse, transfer to oneself) rolls the whole transaction back: no allowance is used up, no key is burned and no row is written.

---

### 2. External Transfer with Simulated Gateway & Reconciler

External transfers reach banks outside our perimeter. Because remote calls can fail, hang or arrive late, PayBridge uses a **3-phase outbox and reconciliation pattern**:

1. **Phase 1: ACCEPT (one fast transaction)**: the amount plus a stepped fee is moved from the sender into the internal `SUSPENSE` account, a `PENDING` transfer and an outbox "to-do note" are saved, and the API answers `HTTP 202 Accepted` in milliseconds.
2. **Phase 2: DISPATCHER (background worker, every 2 s)**: picks up pending outbox notes and calls the bank gateway **strictly outside any database transaction**.
3. **Phase 3: RECONCILER (background worker, every 5 s)**: asks the bank about transfers that have stayed unresolved for more than $10\text{ seconds}$, to reach eventual settlement or an automatic refund.

#### Architecture Flowchart

```mermaid
flowchart TD
    subgraph PHASE1["1. ACCEPT PHASE (synchronous API)"]
        A[Client submits transfer] --> B[Validate payee, minimum, KYC limit]
        B --> C[Debit customer account<br/>Credit SUSPENSE<br/>Amount + stepped fee]
        C --> D[Save transfer: PENDING]
        D --> E[Write outbox record]
        E --> F[Return HTTP 202 Accepted]
    end

    subgraph PHASE2["2. DISPATCHER WORKER (every 2 seconds)"]
        F -.-> G[Dispatcher polls due outbox records]
        G --> H{Call bank gateway<br/>NO DB transaction open}

        H -->|Paid| I[Settle: SUSPENSE to PAYSTACK_SETTLEMENT + FEES<br/>Mark SUCCESS]
        H -->|Refused| J[Reverse the hold: refund amount + fee<br/>Mark FAILED]
        H -->|Accepted, still processing| K[Mark PROCESSING<br/>Wait for reconciler]
        H -->|Timeout / no answer| L[Keep money in SUSPENSE<br/>Retry with exponential backoff]
    end

    subgraph PHASE3["3. RECONCILER WORKER (every 5 seconds)"]
        K -.-> M[Reconciler scans quiet transfers older than 10 s]
        L -.->|after 5 failed attempts| M
        M --> N{Ask the bank: what happened<br/>to this reference?}

        N -->|Success| O[Settle and mark SUCCESS]
        N -->|Refused| P[Full refund<br/>Mark FAILED]
        N -->|Never heard of it| R[Safe to refund<br/>Mark FAILED]
        N -->|Still working| Q[Leave intact, check again later]
    end

    classDef success fill:#064e3b,stroke:#10b981,stroke-width:1px,color:#a7f3d0;
    classDef failure fill:#4c0519,stroke:#f43f5e,stroke-width:1px,color:#fecdd3;
    classDef warning fill:#451a03,stroke:#f59e0b,stroke-width:1px,color:#fde68a;

    class I,O success;
    class J,P,R failure;
    class K,L,Q warning;
```

#### Transfer State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: Transfer created (external: funds held in SUSPENSE)

    PENDING --> SUCCESS: Internal transfer completes in the same transaction
    PENDING --> PROCESSING: Dispatcher picks up the outbox note
    PENDING --> FAILED: Refused before sending

    PROCESSING --> SUCCESS: Bank confirms payout (dispatcher or reconciler)
    PROCESSING --> FAILED: Bank refuses, or confirms it never received it -> full refund

    SUCCESS --> REVERSED: Bank reverses after paying -> settlement and hold both reversed

    FAILED --> [*]: Final (fully refunded)
    REVERSED --> [*]: Final (fully refunded)
```

`FAILED` and `REVERSED` are final, and `TransferStatus.canMoveTo` refuses any illegal move, so a failed transfer can never become a success.

#### The Money Trail (a ₦2,000 transfer with a ₦10 fee, in kobo)

| Stage | Ledger entry | Customer | `SUSPENSE` | `PAYSTACK_SETTLEMENT` | `FEES` |
|---|---|---|---|---|---|
| Accepted | `EXTERNAL_HOLD` | **−201000** | **+201000** | | |
| Bank pays | `EXTERNAL_SETTLEMENT` | | −201000 | +200000 | +1000 |
| *or* bank refuses | `REVERSAL` of the hold | **+201000** | −201000 | | |
| *or* bank reverses later | reverse the settlement, then the hold | **+201000** | | −200000 | −1000 |

At every moment the books balance and every naira is accounted for. `SUSPENSE` returns to zero once every transfer has finished, and the sum of all account balances is always exactly zero.

A late "success" for a transfer that was already refunded raises a *MANUAL REVIEW NEEDED* error and moves no money: it is the one case where the books and the bank genuinely disagree, and a human has to decide.

---

## 🧪 External Bank Simulation Profiles

To test the hard cases reliably, without an unpredictable third-party sandbox, `FakeTransferGateway` decides how to behave from the **last four digits of the payee's account number**:

| Account Suffix | Bank Gateway Reaction | Resolved By | Final Status | Typical Timing |
|---|---|---|---|---|
| `...3333` | **Immediate payout:** the bank accepts and pays | Dispatcher | `SUCCESS` | $\sim 2\text{ s}$ |
| `...1111` | **Immediate refusal** | Dispatcher | `FAILED` *(refunded)* | $\sim 3\text{ s}$ |
| `...1234` (or any other number) | **Accepted, still processing:** the bank queues the payout | Reconciler | `SUCCESS` | $\sim 14\text{ s}$ |
| `...5555` | **Timeout AFTER receipt:** the request was processed but the answer was lost; the retry carries the same reference and is paid exactly once | Dispatcher retry, then Reconciler | `SUCCESS` *(paid exactly once)* | $\sim 19\text{ s}$ |
| `...2222` | **Timeout BEFORE receipt:** the bank never saw the request; after 5 attempts the reconciler learns "never heard of it" | Reconciler | `FAILED` *(refunded)* | $\sim 34\text{ s}$ |
| `...4444` | Accepted, then fails | Reconciler | `FAILED` *(refunded)* | covered by tests |
| `...6666` | Stays pending forever | none | stays `PROCESSING` | covered by tests |
| `...7777` | Succeeds, then the bank reverses it | Reversal handling | `REVERSED` *(refunded)* | covered by tests |
| `...9999` | **Name enquiry failure:** the account does not exist | Web layer | `404 Not Found` | Instant |

The timings were measured on the default settings (`application.yml`): dispatcher every 2 s, reconciler every 5 s for transfers quiet for 10 s, retries after 2, 4, 8 and 16 seconds, giving up on the 5th attempt. The `7777` reversal cannot be triggered by hand until webhooks exist; it is proven by the automated test `aTransferTheBankReversesAfterSucceedingIsFullyRefunded`.

**Fees** are charged on top of the amount (internal transfers are free): up to ₦5,000 costs ₦10, up to ₦50,000 costs ₦25, above that ₦50. The minimum external transfer is ₦100. These are placeholder figures, not Paystack's real pricing.

---

## 📖 API Specification

All amounts are integer **kobo** ($100\text{ kobo} = ₦1.00$). Every transfer needs an `Idempotency-Key` header (8 to 100 characters: letters, digits, `.` `_` `:` `-`).

### 1. Customers
* `POST /api/customers`: onboard a customer (`firstName`, `lastName`, `email`, `phone`). Starts `UNVERIFIED` and `ACTIVE`; the phone is normalised to `+234...`. `201 Created`.
* `GET /api/customers/{id}`: customer profile and KYC tier.
* `PATCH /api/customers/{id}/kyc-tier`: change tier (`UNVERIFIED`, `BASIC`, `FULL`).

### 2. Accounts
* `POST /api/accounts`: open an account with an auto-generated check-digit NUBAN (`customerId`, `currency`, NGN only for now).
* `GET /api/accounts/{id}`: account and cached balance.
* `GET /api/accounts/by-number/{accountNumber}`: look up by 10-digit NUBAN.
* `GET /api/accounts?customerId={id}`: list a customer's accounts.

### 3. Money Transfers
* `POST /api/transfers/internal`: instant account-to-account transfer. `201 Created`, or `200 OK` with `Idempotent-Replayed: true` on a replay.
  * *Headers:* `Idempotency-Key: <unique-string>`
  * *Body:* `{"sourceAccountId": "uuid", "destinationAccountNumber": "1000000024", "amountMinor": 50000, "narration": "rent"}`
* `POST /api/transfers/external`: accept a transfer to another bank. `202 Accepted` (poll the transfer for the outcome), or `200 OK` on a replay.
  * *Headers:* `Idempotency-Key: <unique-string>`
  * *Body:* `{"sourceAccountId": "uuid", "beneficiaryId": "uuid", "amountMinor": 100000, "narration": "invoice"}`
* `GET /api/transfers/{id}`: poll the outcome of a transfer.
* `GET /api/transfers?accountId={id}`: transfers sent or received by an account, newest first.

### 4. Beneficiaries (Name Enquiry)
* `POST /api/beneficiaries/resolve`: name enquiry (`bankCode`, `accountNumber`). Saves nothing.
* `POST /api/beneficiaries`: save a verified payee (`customerId`, `bankCode`, `accountNumber`). Saving the same payee twice is harmless.
* `GET /api/beneficiaries?customerId={id}`: list a customer's saved payees.

### 5. Ledger & Dev Scaffolding
* `GET /api/accounts/{accountId}/entries`: chronological double-entry statement.
* `POST /api/dev/accounts/{accountId}/fund`: **test only**: inject money through a balanced ledger entry from `FUNDING_SOURCE` (`amountMinor`). Controlled by `paybridge.dev.funding-enabled`.

### 6. Compliance & Alerts
* `GET /api/compliance/customers/{customerId}/limits`: daily usage and remaining limit.
* `GET /api/notifications?customerId={customerId}`: SMS and email alerts sent to a customer.

### 7. API Keys (admin only)
* `POST /api/admin/api-keys`: create a key (`name`, `role` of `CLIENT` or `ADMIN`). `201 Created`. The reply contains the key **once** (`Cache-Control: no-store`); it cannot be shown again.
* `GET /api/admin/api-keys`: list keys (name, prefix, role, active). Never shows a key or hash.
* `DELETE /api/admin/api-keys/{id}`: revoke a key. `204`; repeating is harmless.

### 8. Provider Webhooks (open, signed)
* `POST /api/webhooks/paystack` and `POST /api/webhooks/monnify`: a payment provider reports what became of a transfer. **No API key**: the provider proves itself with its signature (`x-paystack-signature` / `monnify-signature`), checked on the raw bytes before anything is read. Answers `200` with `{"result": "APPLIED | DUPLICATE | IGNORED | UNMATCHED | AMOUNT_MISMATCH | MANUAL_REVIEW"}`; `401` bad signature; `400` unreadable body; `404` unknown or unconfigured provider; `409` already being processed; `413` body over 64 KB. A provider is switched off until its secret is set. See [`STEP-12-GUIDE.md`](STEP-12-GUIDE.md).

### 9. Operations
* `GET /actuator/health`: health check. **The only open address.**
* `GET /actuator/metrics`, `/actuator/metrics/{name}`: metrics (admin key). Business meters: `paybridge.transfers` (tags `type`, `outcome`), `paybridge.security.rejections` (tag `reason`), `paybridge.ratelimit.rejections` (tag `limiter`).

### Authentication

Every address except `/actuator/health` needs an API key in the `X-API-Key` header:

| Address | Needs |
|---|---|
| `/actuator/health` (and its liveness/readiness sub-paths) | nothing |
| everything else under `/api` | any valid key (`CLIENT` or `ADMIN`) |
| `/api/admin/**`, `/api/dev/**`, other `/actuator/**` | an `ADMIN` key |

Addresses are cleaned up (`..`, `//`, `;params`, `%`-codes, upper case) before the rule is applied, so disguised addresses cannot reach a protected page. Rate limits per key: 120 requests a minute, and 30 transfer submissions a minute. See [`STEP-13-GUIDE.md`](STEP-13-GUIDE.md).

### Error Format

Every error has the same JSON shape (RFC 9457 problem detail):

```json
{
  "detail": "The Idempotency-Key header is required (a unique value per transfer, 8 to 100 characters)",
  "instance": "/api/transfers/internal",
  "status": 400,
  "title": "Bad Request",
  "code": "VALIDATION_FAILED",
  "requestId": "1d8986f7-d2d4-4e8c-a7d0-2e69e7a7cb24"
}
```

`code` is for programs, `detail` is for people, `requestId` is for support. Field-level validation failures add an `errors` map.

| `code` | HTTP | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bad input, missing `Idempotency-Key`, transfer to oneself, below the minimum |
| `RESOURCE_NOT_FOUND` | 404 | Unknown customer, account, transfer or payee |
| `INSUFFICIENT_FUNDS` | 422 | The balance does not cover the amount (plus the fee, for external transfers) |
| `LIMIT_EXCEEDED` | 422 | Over the KYC tier's per-transaction or daily limit |
| `IDEMPOTENCY_KEY_REUSED` | 422 | The same key was sent with a different request |
| `IDEMPOTENCY_KEY_IN_PROGRESS` | 409 | The first request with that key is still running |
| `ACCOUNT_NOT_ACTIVE`, `INVALID_STATE`, `CURRENCY_MISMATCH`, `CONFLICT`, `GATEWAY_ERROR` | various | Account, state-machine, currency, conflict and provider problems |
| `UNAUTHORIZED` | 401 | No API key, or a key that is invalid or revoked |
| `FORBIDDEN` | 403 | A valid key that is not allowed here (a client key on an admin address) |
| `RATE_LIMITED` | 429 | Over a rate limit, or an address blocked for too many invalid keys. Includes `Retry-After` |
| `INTERNAL_ERROR` | 500 | Anything unexpected; the real cause is only logged, never returned |

---

## 🔍 Observability & Request Logging

Every request the application handles is logged by `AccessLogFilter`, one line per request, after it finishes:

```
INFO  ... [3f9a1c52-...] c.a.p.s.c.AccessLogFilter : POST /api/transfers/internal -> 201 (347 ms) client=mobile-app
WARN  ... [bdfd6ba9-...] c.a.p.s.c.AccessLogFilter : GET /api/customers/... -> 401 (13 ms)
INFO  ... [3f9a1c52-...] c.a.p.n.s.LoggingNotificationSender : [SMS] to +234...: You sent NGN 500.00 ...
INFO  ... [dispatcher-30d7157c] c.a.p.n.s.LoggingNotificationSender : [SMS] ... Your transfer of NGN 2,000.00 was successful
```

* **Request id on every line:** `RequestIdFilter` gives each request an id (the caller's `X-Request-Id` if it is safe, otherwise a fresh UUID). It appears in the log line, the `X-Request-Id` response header and the `requestId` field of every error, so one id ties together a caller's report and the server's logs.
* **Levels:** `5xx` is `ERROR`, `4xx` is `WARN`, everything else `INFO`. A successful `/actuator/health` is `DEBUG` (pollers hit it every few seconds and would bury real traffic); a failing one is still `ERROR`.
* **Who called:** once authenticated, the line ends with `client=<name of the API key>`. The name is logged, never the key. Refused requests (401, 403, 429) are logged too, without a `client`.
* **What is never logged:** request bodies and headers, because they hold personal data and API keys.
* **Background threads keep the id:** an alert sent after a transfer commits carries the id of the request that caused it (carried across threads by Micrometer context propagation). Timer runs get their own tag, such as `dispatcher-30d7157c` or `reconciler-9be2a410`.
* **Metrics:** `/actuator/metrics` (admin key) shows `paybridge.transfers` (committed outcomes only), `paybridge.security.rejections` and `paybridge.ratelimit.rejections`.

The four filters run in this order: request id, access log, API-key check, rate limit. They are registered with `FilterRegistrationBean` and are deliberately **not** `@Component`s: Spring Modulith's observability support wraps module beans in proxies, which breaks a servlet filter that is a bean.

---

## ⚙️ Configuration

Settings live in `src/main/resources/application.yml`.

| Setting | Meaning |
|---|---|
| `paybridge.security.enabled` | API-key checking. On by default. `false` lets every request through (private developer machine only; the app warns at startup). |
| `paybridge.security.bootstrap-admin-key` | The first admin key, read from the **`PAYBRIDGE_ADMIN_API_KEY`** environment variable (at least 32 characters of letters, digits, `_` or `-`; the app refuses to start with a weaker one). Never write it in a file. |
| `paybridge.security.rate-limit.*` | `requests-per-minute` (120, per key), `transfers-per-minute` (30, per key, transfer submissions only), `failed-auth-per-minute` (10, per address, requests carrying an unknown key), `webhooks-per-minute` (600, per address, provider webhooks). |
| `paybridge.webhooks.paystack.secret-key`, `paybridge.webhooks.monnify.secret-key` | Each provider's secret, from `PAYSTACK_SECRET_KEY` / `MONNIFY_SECRET_KEY`. **Empty means that provider is off.** Never write them in a file. |
| `paybridge.webhooks.monnify.signature-scheme` | `hmac-sha512` (default) or `sha512-secret-prefix`: Monnify's documentation describes its signature two ways, so this is switchable. |
| `paybridge.webhooks.max-body-bytes` | Largest webhook body read (65536). |
| `paybridge.dev.funding-enabled` | Switches the test funding endpoint (which also needs an admin key). **Turn it off anywhere but a private machine:** it creates money. |
| `paybridge.gateway` | Which bank sits behind external transfers. `fake` is the built-in simulated bank; `paystack` arrives in Step 11. |
| `paybridge.account.bank-code` | A fictional bank code (`999`) used for the NUBAN check digit. |
| `paybridge.compliance.limits` | Per-tier limits in kobo. UNVERIFIED: ₦50,000 per transfer, ₦100,000 per day. BASIC: ₦200,000 / ₦500,000. FULL: ₦5,000,000 / ₦10,000,000. Placeholder figures. |
| `paybridge.transfer.external-min-minor`, `external-fees` | Minimum external transfer (₦100) and the fee bands. Placeholder figures. |
| `paybridge.dispatcher.*` | `enabled`, `interval` (2 s), `batch-size`, `max-attempts` (5), `base-delay` (2 s, doubling). |
| `paybridge.reconciler.*` | `enabled`, `interval` (5 s), `min-age` (10 s), `batch-size`. |
| `logging.pattern.correlation` | Puts the request id on each log line. Uses Log4j2 syntax (`%X{requestId}`). |

The test profile (`src/test/resources/application-test.yml`) switches the dispatcher and reconciler timers off so tests can drive them step by step.

---

## 🚀 Getting Started

### Prerequisites
* **Java 25**
* **Docker Desktop**, running (Spring starts PostgreSQL for you from `compose.yaml`, and the tests start a throwaway one)
* The Maven Wrapper (`mvnw`) is included; no separate Maven install is needed

### Run the Backend

1. **Choose an admin key and start the application.** The first admin key comes from the environment (at least 32 characters; letters, digits, `_` or `-`):
   ```bash
   export PAYBRIDGE_ADMIN_API_KEY=$(openssl rand -hex 24)   # remember it: it is not stored anywhere readable
   ./mvnw spring-boot:run
   ```
   Look for `Registered the bootstrap admin API key.` in the log. Without the variable, every protected endpoint answers `401`.
   Spring Boot starts the PostgreSQL container from `compose.yaml` automatically, applies Flyway migrations `V1` to `V8`, and boots on port `8080`. Wait for the line containing `Started PayBridgeApplication`.

   The database is mapped to host port **5433** (not 5432), so it does not clash with a PostgreSQL already installed on the machine.

2. **Verify health:**
   ```bash
   curl -s http://localhost:8080/actuator/health
   # {"groups":["liveness","readiness"],"status":"UP"}
   ```

3. **Create a key for a caller** (the reply shows the key **once**; copy it):
   ```bash
   BASE=http://localhost:8080; H='Content-Type: application/json'
   curl -s -X POST $BASE/api/admin/api-keys -H "$H" -H "X-API-Key: $PAYBRIDGE_ADMIN_API_KEY" \
     -d '{"name":"mobile-app","role":"CLIENT"}'
   ```

4. **Try it.** Use that key on the business endpoints:
   ```bash
   CLIENT=<the apiKey from step 3>
   curl -s -X POST $BASE/api/customers -H "$H" -H "X-API-Key: $CLIENT" \
     -d '{"firstName":"Ada","lastName":"Obi","email":"ada1@example.com","phone":"08012345678"}'
   ```
   Then follow the response ids through the endpoints in the [API Specification](#-api-specification). Test funding (`/api/dev/...`) needs the **admin** key.

If `Port 8080 was already in use`, an earlier copy of the app is still running: find it with `netstat -ano | grep 8080` and stop it.

> **Note:** keys travel in a header, so use HTTPS anywhere the app is reachable by others. The rate limits are held in memory, which is right for one running copy of the app.

---

## 🧪 Testing & Verification

```bash
./mvnw test
```

**488 tests, all passing.** Most start a real PostgreSQL through Testcontainers, so **Docker must be running**. If many tests fail at once with "ApplicationContext failure threshold exceeded", check Docker first.

### Key Test Suites

* **Concurrency Race Tests (`ConcurrencyTest`, 5 tests):** fires 40 and 200 simultaneous transfers (including opposite directions) and proves no account is overdrawn, there is no deadlock, and all balances still sum to exactly zero.
* **Duplicate Request Race (`DuplicateRequestRaceTest`):** sends concurrent requests with the same `Idempotency-Key` and verifies only one transfer executes while the rest receive replays.
* **Internal Transfers (`InternalTransferTest`, 21 tests):** the happy path, replays, key reuse, limits, overdraw, and the rollback of every refused request.
* **Ledger (`LedgerServiceTest`, 19 tests, plus `LedgerEndpointsTest`):** balanced postings, reversals, replay safety, and the append-only database triggers.
* **External Transfers (`ExternalTransferTest`, 28 tests; `BeneficiaryTest`, 8):** outbox dispatch, timeout handling, retries with backoff, refunds, reversal after success, late success after a refund, and every fake-bank profile.
* **Modularity (`ModularityTests`):** verifies the Spring Modulith boundaries, so no unauthorised cross-module dependency exists.
* **Access Log (`AccessLogFilterTest`, 12 tests):** listens to the filter's real logger and checks each level, the query string, the exception case, the caller's name, and that bodies and headers never appear. Needs no Docker.
* **API Keys (`ApiKeyServiceTest`, 18 tests; `AdminKeyBootstrapTest`, 6):** keys are random and unique, **only the hash is stored**, unknown / altered / revoked keys are refused, a weak bootstrap key stops startup without echoing it.
* **Security Filters (`SecurityFilterTest`, 25 tests; `ApiKeyAdminEndpointsTest`, 11; `SecurityFilterRegistrationTest`, 2):** 401 / 403 / 429 in the standard error shape, admin-only endpoints, disguised addresses cannot escalate, guessing blocks even a right key, per-key and transfer rate limits, and the running app installs the filters in order.
* **Rate Limiter, Path Policy, Error JSON (`RateLimiterTest`, 9; `AccessPolicyTest`, 35; `ProblemJsonTest`, 4):** including 200 simultaneous threads never exceeding a limit. No Docker.
* **Request Id Across Threads (`MdcPropagationTest`, 9; `AsyncExecutorWiringTest`, 1):** the application's **real** executor carries the id; timers get tagged ids.
* **Metrics (`TransferMetricsTest`, 3):** committed transfers are counted; refused ones and replays are not.
* **Unit tests:** `Money`, `Nuban`, error codes and exceptions, the transfer state machine, fee bands, compliance and notifications.

The shared database is reused across tests, so each test creates its own customers and accounts, and the key invariant (all balances sum to zero) is checked after money-moving tests.

---

## 📌 Project Status

| Step | What | Status |
|---|---|---|
| 1 | Project skeleton: build, config, database, foundation tests | ✅ |
| 2 | `shared`: `Money`, error model, auditing, correlation ids | ✅ (`MoneyTest` now 45 tests; the gaps are closed) |
| 3 | `customer` | ✅ |
| 4 | `account`: NUBAN numbers, balances, system accounts | ✅ |
| 5 | `ledger`: balanced, append-only posting | ✅ |
| 6 | Concurrency hardening | ✅ |
| 7 | Internal transfers with idempotency | ✅ |
| 8 | `compliance`: per-tier limits | ✅ |
| 9 | `notification`: event-driven alerts | ✅ |
| 10 | External transfers: fake gateway, outbox, fees, reversals | ✅ |
| 11 | Real Paystack client | ⏸ on hold (needs a Paystack test account) |
| 12 | Provider webhooks (Paystack and Monnify format): signature, de-duplication, cross-check, manual review | ✅ (see [`STEP-12-GUIDE.md`](STEP-12-GUIDE.md); payload field names not yet compared with a live delivery) |
| 13 | Hardening: API-key authentication, rate limiting, request logging, metrics, request id on background threads | ✅ (see [`STEP-13-GUIDE.md`](STEP-13-GUIDE.md)) |

### Not built yet

* **The real bank connection (Step 11):** `paybridge.gateway` is `fake`. The code talks to a `TransferGateway` interface, so the real provider is one new class.
* **Real provider clients:** webhooks are built, but initiating transfers with Paystack or Monnify is not. The webhook payload field names come from third-party guides and must be compared with a real sandbox delivery (the Monnify sandbox may not sign webhooks at all).
* **Security gaps worth knowing about:** rate limits are in memory (right for one running copy; several copies would need a shared store); guess protection depends on the caller's address being right behind a hosting proxy (configured, but not tested behind a real one); roles are coarse (any client key can read any customer); no key expiry or "last used" time.
* **Later phases:** `fx`, `remittance`, `settlement` and full AML screening. A detailed, code-free execution plan (Steps 14 to 28, with decision records) is in [`plans/fx-remittance-settlement.plan.md`](plans/fx-remittance-settlement.plan.md).
* **Placeholders to check before trusting:** the fee bands, the per-tier limits and the fictional bank code `999`.

### Not part of this copy

This copy is the backend only. A React front end, Swagger UI, Docker and Render deployment files, and the step-by-step demo guides exist in the separate `paybridge(learning copy)` folder and have not been brought across.

---

## 📚 Further Reading

* [`STEP-12-GUIDE.md`](STEP-12-GUIDE.md): webhooks explained line by line, with analogies, a demo recipe using `openssl`, what is and is not verified about each provider, and self-test questions.
* [`plans/fx-remittance-settlement.plan.md`](plans/fx-remittance-settlement.plan.md): the execution plan for the `fx`, `remittance` and `settlement` modules (no code).
* [`STEP-13-GUIDE.md`](STEP-13-GUIDE.md): the security, rate-limiting and observability work explained line by line, with analogies, end-to-end traces and self-test questions.
* [`BUILD-LOG.md`](BUILD-LOG.md): every decision with its reason, the problems met and how they were solved, the testing strategy, what is left, and a glossary.
* [`Sandbox.md`](Sandbox.md): from the assignment, a list of payment providers' free test environments.
* `compose.yaml`: the local PostgreSQL container; `src/main/resources/db/migration`: the Flyway migrations.


## FX, remittance and settlement (Steps 14 to 28)

PayBridge can now take USD from a corridor partner, convert it to naira at a locked quote, pay the recipient (inside PayBridge or at another bank), refund on failure, and settle with the partner. See [FX-REMITTANCE-SETTLEMENT-GUIDE.md](FX-REMITTANCE-SETTLEMENT-GUIDE.md) for how it works and how to drive it with the built-in fakes. All rates, limits and screening data are placeholders.
