# PayBridge — Missing Tests & Test Debt Catalog

> **Living Test Catalog & Test Debt Register**  
> **Last Updated:** October 19, 2026 (Updated for Steps 1–28)  
> This file tracks the test suite status, paid-down test debt, and all newly identified missing unit, slice, and architecture tests for the **PayBridge** codebase.  
> It is categorized by **Package**, **Class**, and **Function/Method** so it is immediately clear where each test belongs.

---

## 1. Test Suite Status & Coverage Overview

| Status Marker | Meaning |
|---|---|
| ✅ **Covered** | Implemented in `src/test/java` and passing in the build |
| ⏳ **Missing** | Test identified, snippet drafted below, ready to be added |
| 🔄 **In Progress** | Test is being drafted or verified |

### Current Suite Metrics
- **Total Executing Test Suites:** 67 test classes
- **Total Passing Tests:** 865 tests passing, 1 skipped (All green, 0 failures, 0 errors)
- **Paid-Down Test Debt:** All previously identified `MoneyTest` test debt (sign predicates, `negate()`, `compareTo()`, immutability guarantees, factory methods, equals/hashCode) has been fully implemented in [`MoneyTest.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/test/java/com/academy/paybridge/shared/money/MoneyTest.java).
- **Current Test Debt Focus:** Targeted unit tests for utility/security/audit classes (`AuditableEntity`, `JpaAuditingConfig`, `RequestIdFilter`, `IdempotencyKeyHeader`, `TransferRules`, `TransferOutbox`, `ApiKeyHasher`, `SettlementMetrics`).

---

## 2. Test Debt Resolution Log (Paid Down ✅)

The following tests were previously recorded as missing and have now been implemented in the codebase:

| Package | Class | Feature / Method | Current Status |
|---|---|---|:---:|
| `com.academy.paybridge.shared.money` | `Money` | `negate()` (positive, negative, zero, identity) | ✅ Covered |
| `com.academy.paybridge.shared.money` | `Money` | `isPositive()`, `isNegative()`, `isZero()` | ✅ Covered |
| `com.academy.paybridge.shared.money` | `Money` | `compareTo()` & currency mismatch guard | ✅ Covered |
| `com.academy.paybridge.shared.money` | `Money` | Value object immutability checks | ✅ Covered |
| `com.academy.paybridge.shared.money` | `Money` | `equals()` and `hashCode()` contract | ✅ Covered |
| `com.academy.paybridge.shared.money` | `Money` | Factory methods (`ngn()`, `ofMinor()`, `zero()`) | ✅ Covered |
| `com.academy.paybridge.shared.exception` | `ErrorCode` | Enum status mappings and enum integrity | ✅ Covered |
| `com.academy.paybridge.shared.exception` | `GlobalExceptionHandler` | Status mapping, RFC 9457 JSON problem details | ✅ Covered |
| `com.academy.paybridge.shared.exception` | `CurrencyMismatchException` | Subclassing, message formatting | ✅ Covered |
| `com.academy.paybridge.transfer` | `FeeCalculator` | Band resolution, boundary amounts, ordering | ✅ Covered |
| `com.academy.paybridge.compliance` | `ScreeningPolicy` | Name similarity, AML corroborators & outcomes | ✅ Covered |
| `com.academy.paybridge.security` | `RateLimiter` | Sliding window rate limits | ✅ Covered |

---

## 3. Identified Missing Tests by Package & Class

---

### 3.1 Package: `com.academy.paybridge.shared.audit`

#### Class: `AuditableEntity`
- **Source File:** [`AuditableEntity.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/audit/AuditableEntity.java)
- **Target Test File:** `com.academy.paybridge.shared.audit.AuditableEntityTest`
- **Status:** ⏳ Missing
- **Why it matters:** Financial regulations require tamper-evident timestamps. If JPA Auditing fails or is misconfigured, database rows will have null timestamps, breaking statement matching, reconciliation, and audit logs.

```java
package com.academy.paybridge.shared.audit;

import com.academy.paybridge.TestcontainersConfiguration;
import com.academy.paybridge.shared.config.JpaAuditingConfig;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class AuditableEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DummyAuditedRepository repository;

    @Test
    void setsCreatedAndUpdatedTimestampsOnPersist() {
        DummyAuditedEntity entity = new DummyAuditedEntity("Account-001");
        Instant before = Instant.now();

        DummyAuditedEntity saved = repository.saveAndFlush(entity);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void updatesLastModifiedDateWhilePreservingCreatedAtOnUpdate() throws InterruptedException {
        DummyAuditedEntity entity = repository.saveAndFlush(new DummyAuditedEntity("Initial"));
        Instant originalCreatedAt = entity.getCreatedAt();
        Instant originalUpdatedAt = entity.getUpdatedAt();

        Thread.sleep(20);
        entity.setName("Modified");
        DummyAuditedEntity updated = repository.saveAndFlush(entity);
        entityManager.refresh(updated);

        assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
    }

    @Entity
    @Table(name = "test_dummy_audited")
    static class DummyAuditedEntity extends AuditableEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public DummyAuditedEntity() {}
        public DummyAuditedEntity(String name) { this.name = name; }
        public void setName(String name) { this.name = name; }
        public String getName() { return name; }
        public Long getId() { return id; }
    }

    @Repository
    interface DummyAuditedRepository extends JpaRepository<DummyAuditedEntity, Long> {}
}
```

---

### 3.2 Package: `com.academy.paybridge.shared.config`

#### Class: `JpaAuditingConfig`
- **Source File:** [`JpaAuditingConfig.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/config/JpaAuditingConfig.java)
- **Target Test File:** `com.academy.paybridge.shared.config.JpaAuditingConfigTest`
- **Status:** ⏳ Missing
- **Why it matters:** Ensures the auditing provider produces UTC instants exclusively, avoiding local timezone skew in cross-border settlements.

```java
package com.academy.paybridge.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JpaAuditingConfigTest {

    private final JpaAuditingConfig config = new JpaAuditingConfig();

    @Test
    void clockBeanProvidesUtcClock() {
        Clock clock = config.clock();

        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void auditingDateTimeProviderReturnsCurrentInstantFromProvidedClock() {
        Instant fixedInstant = Instant.parse("2026-10-19T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        DateTimeProvider provider = config.auditingDateTimeProvider(fixedClock);
        Optional<TemporalAccessor> now = provider.getNow();

        assertThat(now).isPresent();
        assertThat(now.get()).isEqualTo(fixedInstant);
    }
}
```

---

#### Class: `RequestIdFilter`
- **Source File:** [`RequestIdFilter.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/config/RequestIdFilter.java)
- **Target Test File:** `com.academy.paybridge.shared.config.RequestIdFilterTest`
- **Status:** ⏳ Missing
- **Why it matters:** Protects logs against CRLF injection and malicious headers, propagates correlation IDs through MDC, and cleans up MDC context to prevent thread-pool leakage.

```java
package com.academy.paybridge.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void keepsSafeIncomingRequestId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "client-req-123_abc.xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("client-req-123_abc.xyz");
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("client-req-123_abc.xyz");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull(); // cleaned up in finally
    }

    @Test
    void generatesFreshUuidWhenHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNotBlank();
        });

        String responseId = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(responseId).isNotNull();
        assertThat(java.util.UUID.fromString(responseId)).isNotNull();
    }

    @Test
    void replacesUnsafeOrOverlongHeaderWithFreshUuid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Header with spaces and CRLF injection attempt
        request.addHeader(RequestIdFilter.HEADER_NAME, "bad\r\ninjection id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        String responseId = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(responseId).doesNotContain("\r", "\n", " ");
        assertThat(java.util.UUID.fromString(responseId)).isNotNull();
    }
}
```

---

### 3.3 Package: `com.academy.paybridge.shared.web`

#### Class: `IdempotencyKeyHeader`
- **Source File:** [`IdempotencyKeyHeader.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/web/IdempotencyKeyHeader.java)
- **Target Test File:** `com.academy.paybridge.shared.web.IdempotencyKeyHeaderTest`
- **Status:** ⏳ Missing
- **Why it matters:** Client retries on money endpoints (remittance, quotes) rely on idempotency headers. Without validation, malformed or empty headers could bypass duplicate detection.

```java
package com.academy.paybridge.shared.web;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyHeaderTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "valid-key-123",
            "IDEM_KEY:999.000",
            "abcdefgh",                      // exactly 8 chars
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789" // 100 chars
    })
    void acceptsValidKeys(String key) {
        assertThat(IdempotencyKeyHeader.require(key)).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "short",                         // under 8 chars
            "has space 1234",                // spaces not allowed
            "bad$char*here123"               // special characters
    })
    void rejectsInvalidKeys(String key) {
        assertThatThrownBy(() -> IdempotencyKeyHeader.require(key))
                .isInstanceOf(PayBridgeException.class)
                .satisfies(e -> assertThat(((PayBridgeException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> IdempotencyKeyHeader.require(null))
                .isInstanceOf(PayBridgeException.class);
    }
}
```

---

### 3.4 Package: `com.academy.paybridge.account.domain`

#### Class: `Account`
- **Source File:** [`Account.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/account/domain/Account.java)
- **Target Test File:** `com.academy.paybridge.account.domain.AccountTest`
- **Status:** ⏳ Missing
- **Why it matters:** Account status transitions (`freeze()`, `activate()`) and balance invariants must be verified in fast unit tests without requiring database transactions.

```java
package com.academy.paybridge.account.domain;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void customerAccountStartsActiveWithZeroBalance() {
        Account account = Account.openForCustomer("0123456789", UUID.randomUUID(), "NGN");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalanceMinor()).isZero();
        assertThat(account.getAccountNumber()).isEqualTo("0123456789");
    }

    @Test
    void cannotApplyBalanceChangeWhenFrozen() {
        Account account = Account.openForCustomer("0123456789", UUID.randomUUID(), "NGN");
        account.freeze();

        assertThatThrownBy(() -> account.applyBalanceChange(10_000))
                .isInstanceOf(PayBridgeException.class)
                .satisfies(e -> assertThat(((PayBridgeException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
    }

    @Test
    void canBeReactivatedAfterFreeze() {
        Account account = Account.openForCustomer("0123456789", UUID.randomUUID(), "NGN");
        account.freeze();
        account.activate();

        account.applyBalanceChange(10_000);
        assertThat(account.getBalanceMinor()).isEqualTo(10_000);
    }

    @Test
    void customerAccountCannotGoNegative() {
        Account account = Account.openForCustomer("0123456789", UUID.randomUUID(), "NGN");

        assertThatThrownBy(() -> account.applyBalanceChange(-1))
                .isInstanceOf(InsufficientFundsException.class);
    }
}
```

---

### 3.5 Package: `com.academy.paybridge.customer.service`

#### Class: `CustomerService` (Phone Normalization)
- **Source File:** [`CustomerService.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/customer/service/CustomerService.java)
- **Target Test File:** `com.academy.paybridge.customer.service.CustomerServiceUnitTest`
- **Status:** ⏳ Missing
- **Why it matters:** Inconsistent phone formatting breaks SMS alerting and customer identity checks across modules.

```java
package com.academy.paybridge.customer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServiceUnitTest {

    @ParameterizedTest
    @CsvSource({
            "08012345678,    +2348012345678",
            "07098765432,    +2347098765432",
            "+2348012345678, +2348012345678",
            "  08012345678 , +2348012345678"
    })
    void normalisesNigerianPhoneNumbers(String raw, String expected) {
        assertThat(CustomerService.normalisePhone(raw)).isEqualTo(expected);
    }
}
```

---

### 3.6 Package: `com.academy.paybridge.transfer.service`

#### Class: `TransferRules`
- **Source File:** [`TransferRules.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/transfer/service/TransferRules.java)
- **Target Test File:** `com.academy.paybridge.transfer.service.TransferRulesTest`
- **Status:** ⏳ Missing
- **Why it matters:** Validates that system accounts (like Suspense, Fees) cannot be accidentally specified as source/destination accounts in user-initiated transfer endpoints.

```java
package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferRulesTest {

    @Test
    void rejectsSystemAccountsAsCustomerAccountRole() {
        AccountSummary systemAccount = new AccountSummary(
                UUID.randomUUID(), null, "FEE_COLLECTION", null, "NGN", 0, AccountStatus.ACTIVE, 1);

        assertThatThrownBy(() -> TransferRules.requireUsableCustomerAccount(systemAccount, "Source"))
                .isInstanceOf(PayBridgeException.class)
                .satisfies(e -> assertThat(((PayBridgeException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsInactiveCustomerAccount() {
        AccountSummary inactiveCustomer = new AccountSummary(
                UUID.randomUUID(), "0123456789", null, UUID.randomUUID(), "NGN", 5000, AccountStatus.FROZEN, 1);

        assertThatThrownBy(() -> TransferRules.requireUsableCustomerAccount(inactiveCustomer, "Destination"))
                .isInstanceOf(PayBridgeException.class)
                .satisfies(e -> assertThat(((PayBridgeException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
    }

    @Test
    void acceptsActiveCustomerAccount() {
        AccountSummary activeCustomer = new AccountSummary(
                UUID.randomUUID(), "0123456789", null, UUID.randomUUID(), "NGN", 5000, AccountStatus.ACTIVE, 1);

        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> TransferRules.requireUsableCustomerAccount(activeCustomer, "Source"));
    }
}
```

---

### 3.7 Package: `com.academy.paybridge.transfer.domain`

#### Class: `TransferOutbox`
- **Source File:** [`TransferOutbox.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/transfer/domain/TransferOutbox.java)
- **Target Test File:** `com.academy.paybridge.transfer.domain.TransferOutboxTest`
- **Status:** ⏳ Missing
- **Why it matters:** The transactional outbox pattern guarantees external payout delivery. Retry scheduling, attempt incrementation, and string truncation (500 chars limit) must work without data loss or exceptions.

```java
package com.academy.paybridge.transfer.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferOutboxTest {

    @Test
    void startsPendingWithZeroAttempts() {
        UUID transferId = UUID.randomUUID();
        Instant now = Instant.now();
        TransferOutbox outbox = TransferOutbox.forTransfer(transferId, now);

        assertThat(outbox.getStatus()).isEqualTo(TransferOutbox.Status.PENDING);
        assertThat(outbox.getAttempts()).isZero();
        assertThat(outbox.getTransferId()).isEqualTo(transferId);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(now);
    }

    @Test
    void scheduleRetryIncrementsAttemptsAndTruncatesError() {
        TransferOutbox outbox = TransferOutbox.forTransfer(UUID.randomUUID(), Instant.now());
        Instant retryTime = Instant.now().plusSeconds(60);
        String veryLongError = "E".repeat(1000);

        outbox.scheduleRetry(retryTime, veryLongError);

        assertThat(outbox.getAttempts()).isEqualTo(1);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(retryTime);
        assertThat(outbox.getLastError()).hasSize(500);
    }

    @Test
    void giveUpMarksStatusDoneAndRecordsError() {
        TransferOutbox outbox = TransferOutbox.forTransfer(UUID.randomUUID(), Instant.now());

        outbox.giveUp("Provider 500 Gateway Timeout");

        assertThat(outbox.getStatus()).isEqualTo(TransferOutbox.Status.DONE);
        assertThat(outbox.getAttempts()).isEqualTo(1);
        assertThat(outbox.getLastError()).contains("Gave up sending after 1 attempts");
    }
}
```

---

### 3.8 Package: `com.academy.paybridge.security.service`

#### Class: `ApiKeyHasher`
- **Source File:** [`ApiKeyHasher.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/security/service/ApiKeyHasher.java)
- **Target Test File:** `com.academy.paybridge.security.service.ApiKeyHasherTest`
- **Status:** ⏳ Missing
- **Why it matters:** Verifies standard SHA-256 hash outputs and deterministic 64-character hex strings for client authentication tokens.

```java
package com.academy.paybridge.security.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {

    @Test
    void hashesEmptyStringDeterministically() {
        // Standard SHA-256 hash of empty string
        String hash = ApiKeyHasher.sha256Hex("");

        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void returnsLowercase64HexCharacters() {
        String hash = ApiKeyHasher.sha256Hex("pb_live_secret_key_12345");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[a-f0-9]{64}$");
    }
}
```

---

### 3.9 Package: `com.academy.paybridge.settlement.service`

#### Class: `SettlementMetrics`
- **Source File:** [`SettlementMetrics.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/settlement/service/SettlementMetrics.java)
- **Target Test File:** `com.academy.paybridge.settlement.service.SettlementMetricsTest`
- **Status:** ⏳ Missing
- **Why it matters:** Ensures Micrometer gauge registration for open settlement breaks by age buckets (`0-1d`, `2-7d`, `>7d`) and error-resilience during database read failures.

```java
package com.academy.paybridge.settlement.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class SettlementMetricsTest {

    @Test
    void registersAgeGaugesAndHandlesQuerySafely() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(5);

        new SettlementMetrics(registry, jdbc);

        assertThat(registry.find("paybridge.settlement.breaks.open").tag("age", "0-1d").gauge()).isNotNull();
        assertThat(registry.find("paybridge.settlement.breaks.open").tag("age", "2-7d").gauge()).isNotNull();
        assertThat(registry.find("paybridge.settlement.breaks.open").tag("age", ">7d").gauge()).isNotNull();
        assertThat(registry.find("paybridge.settlement.breaks.open").tag("age", "0-1d").gauge().value()).isEqualTo(5.0);
    }
}
```

---

### 3.10 Package: `com.academy.paybridge` (Architecture Documentation)

#### Class: `ModularityTests`
- **Source File:** [`ModularityTests.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/test/java/com/academy/paybridge/ModularityTests.java)
- **Status:** ⏳ Missing
- **Why it matters:** Automatically generates Spring Modulith PlantUML and Asciidoc component canvases on build.

```java
    @Test
    void generateModulithDocumentation() {
        org.springframework.modulith.core.ApplicationModules modules = 
                org.springframework.modulith.core.ApplicationModules.of(PayBridgeApplication.class);
        new org.springframework.modulith.docs.Documenter(modules)
                .writeDocumentation()
                .writeModulesAsPlantUml();
    }
```

---

## 4. Maintenance Workflow

1. As new domain models, rules, or utilities are added to the codebase, append their test specifications and snippets to this file under their respective package and class.
2. When paying down test debt, add the snippet to the corresponding test file in `src/test/java` and move the entry from Section 3 into Section 2 (**Test Debt Resolution Log**).
