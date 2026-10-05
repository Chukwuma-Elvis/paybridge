# PayBridge — Missing Tests & Test Debt Catalog

> **Living Test Catalog & Test Debt Register**  
> This file tracks all identified missing unit, integration, and architecture tests for the **PayBridge** codebase.  
> It is categorized by **Package**, **Class**, and **Function/Method** so it is immediately clear where each test belongs.  
> As new code or functions are added to PayBridge, this document is updated with corresponding test specifications and ready-to-run snippets.

---

## 1. Test Suite Status & Overview

| Status Marker | Meaning |
|---|---|
| ⏳ **Missing** | Test identified, snippet drafted, needs implementation in test suite |
| 🔄 **In Progress** | Test is currently being written or integrated |
| ✅ **Covered** | Test exists in `src/test/java` and passes |

### Current Coverage Summary
- **Current Passing Tests in Codebase:** 32 tests (`ModularityTests`: 1, `PayBridgeApplicationTests`: 1, `MoneyTest`: 9, `ErrorCodeTest`: 11, `PayBridgeExceptionTest`: 3, `CurrencyMismatchExceptionTest`: 4, `GlobalExceptionHandlerTest`: 3).
- **Total Missing Tests Identified:** 24 test cases across 6 classes.
- **Priority Areas:** `com.academy.paybridge.shared.money.Money` (core invariants & operations) and `com.academy.paybridge.shared.audit` / `config` (JPA auditing lifecycle).

---

## 2. Package: `com.academy.paybridge.shared.money`

### Class: `Money`
- **Source File:** [`Money.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/money/Money.java)
- **Target Test File:** [`MoneyTest.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/test/java/com/academy/paybridge/shared/money/MoneyTest.java)

#### Method: `public Money negate()`
- **Status:** ⏳ Missing (Only `Long.MIN_VALUE` overflow is currently tested)
- **Why it matters:** In double-entry accounting, ledger entries use signed integers. Reversals and offsetting debit/credit lines require calling `negate()`. We must guarantee that positive numbers become negative, negative become positive, and zero remains zero without loss or sign-corruption.
- **Snippet to add to `MoneyTest`:**
```java
    @Test
    void negatesPositiveNegativeAndZeroCorrectly() {
        Money positive = Money.ngn(10_000);
        Money negative = Money.ngn(-10_000);
        Money zero = Money.ngn(0);

        assertThat(positive.negate()).isEqualTo(Money.ngn(-10_000));
        assertThat(negative.negate()).isEqualTo(Money.ngn(10_000));
        assertThat(zero.negate()).isEqualTo(Money.ngn(0));
    }
```

---

#### Methods: `public boolean isPositive()`, `public boolean isNegative()`, `public boolean isZero()`
- **Status:** ⏳ Missing
- **Why it matters:** Domain business rules (such as checking if a customer balance is overdrawn, or validating that a transfer request amount is strictly positive) rely on these predicates.
- **Snippet to add to `MoneyTest`:**
```java
    @Test
    void verifiesSignPredicatesAcrossPositiveNegativeAndZero() {
        Money positive = Money.ngn(5_000);
        Money negative = Money.ngn(-5_000);
        Money zero = Money.ngn(0);

        // Positive tests
        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.isNegative()).isFalse();
        assertThat(positive.isZero()).isFalse();

        // Negative tests
        assertThat(negative.isPositive()).isFalse();
        assertThat(negative.isNegative()).isTrue();
        assertThat(negative.isZero()).isFalse();

        // Zero tests
        assertThat(zero.isPositive()).isFalse();
        assertThat(zero.isNegative()).isFalse();
        assertThat(zero.isZero()).isTrue();
    }
```

---

#### Method: `public Money subtract(Money other)` — Currency Mismatch Guard
- **Status:** ⏳ Missing
- **Why it matters:** While `add()` tests that mixing currencies throws `CurrencyMismatchException`, `subtract()` is not tested for currency mismatch. We must verify that attempting to subtract dollars from naira throws `CurrencyMismatchException`.
- **Snippet to add to `MoneyTest`:**
```java
    @Test
    void refusesToSubtractDifferentCurrencies() {
        Money naira = Money.ngn(10_000);
        Money usd = Money.ofMinor(500, Currency.getInstance("USD"));

        assertThatThrownBy(() -> naira.subtract(usd))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessage("Currency mismatch: NGN vs USD");
    }
```

---

#### Method: `public int compareTo(Money other)`
- **Status:** ⏳ Missing
- **Why it matters:** Compares balances and amounts for limits, sufficient balance checks, and sorting. It must establish a strict natural ordering for identical currencies and refuse comparison across mismatched currencies.
- **Snippet to add to `MoneyTest`:**
```java
    @Test
    void comparesMoneyWithinSameCurrency() {
        Money small = Money.ngn(1_000);
        Money medium = Money.ngn(5_000);
        Money duplicate = Money.ngn(1_000);

        assertThat(small.compareTo(medium)).isNegative();
        assertThat(medium.compareTo(small)).isPositive();
        assertThat(small.compareTo(duplicate)).isZero();
    }

    @Test
    void refusesToCompareDifferentCurrencies() {
        Money naira = Money.ngn(5_000);
        Money euro = Money.ofMinor(5_000, Currency.getInstance("EUR"));

        assertThatThrownBy(() -> naira.compareTo(euro))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessage("Currency mismatch: NGN vs EUR");
    }
```

---

#### Factory Methods: `ofMinor(long, Currency)`, `ngn(long)`, `zero(Currency)`
- **Status:** ⏳ Missing
- **Why it matters:** Ensures factories bind the right minor unit and currency objects without inversion or defaulting errors.
- **Snippet to add to `MoneyTest`:**
```java
    @Test
    void factoryMethodsConstructExpectedMoneyValues() {
        Currency gbp = Currency.getInstance("GBP");
        Money pounds = Money.ofMinor(1_250, gbp);

        assertThat(pounds.minorUnits()).isEqualTo(1_250);
        assertThat(pounds.currency()).isEqualTo(gbp);

        Money naira = Money.ngn(75_000);
        assertThat(naira.minorUnits()).isEqualTo(75_000);
        assertThat(naira.currency()).isEqualTo(Money.NGN);

        Money zeroGbp = Money.zero(gbp);
        assertThat(zeroGbp.minorUnits()).isZero();
        assertThat(zeroGbp.currency()).isEqualTo(gbp);
    }
```

---

#### Value Object Contract: Immutability Verification
- **Status:** ⏳ Missing
- **Why it matters:** `Money` must be purely immutable. Calling `add`, `subtract`, or `negate` must return a brand-new instance and leave original variables unmodified.
- **Snippet to add to `MoneyTest`:**
```java
    @Test
    void operationsDoNotMutateOriginalInstances() {
        Money original = Money.ngn(50_000);
        Money delta = Money.ngn(10_000);

        Money sum = original.add(delta);
        assertThat(original.minorUnits()).isEqualTo(50_000);
        assertThat(sum.minorUnits()).isEqualTo(60_000);

        Money diff = original.subtract(delta);
        assertThat(original.minorUnits()).isEqualTo(50_000);
        assertThat(diff.minorUnits()).isEqualTo(40_000);

        Money negated = original.negate();
        assertThat(original.minorUnits()).isEqualTo(50_000);
        assertThat(negated.minorUnits()).isEqualTo(-50_000);
    }
```

---

#### Value Object Contract: `equals(Object)` and `hashCode()`
- **Status:** ⏳ Missing
- **Why it matters:** `Money` instances are stored in collections, sets, and used in assertions. Equal amounts in equal currencies must equal and share the same hash code; different currencies or amounts must not equal.
- **Snippet to add to `MoneyTest`:**
```java
    @Test
    void equalsAndHashCodeContract() {
        Money a1 = Money.ngn(50_000);
        Money a2 = Money.ngn(50_000);
        Money differentAmount = Money.ngn(25_000);
        Money differentCurrency = Money.ofMinor(50_000, Currency.getInstance("USD"));

        // Reflexive & Symmetric
        assertThat(a1).isEqualTo(a1);
        assertThat(a1).isEqualTo(a2);
        assertThat(a2).isEqualTo(a1);
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode());

        // Different values
        assertThat(a1).isNotEqualTo(differentAmount);
        assertThat(a1).isNotEqualTo(differentCurrency);
        assertThat(a1).isNotEqualTo(null);
        assertThat(a1).isNotEqualTo("50000 NGN");
    }
```

---

## 3. Package: `com.academy.paybridge.shared.audit`

### Class: `AuditableEntity`
- **Source File:** [`AuditableEntity.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/audit/AuditableEntity.java)
- **Target Test File:** `com.academy.paybridge.shared.audit.AuditableEntityTest` (New Test Class)
- **Current Status:** ⏳ **0 Tests (Total test debt)**
- **Why it matters:** Auditing is critical for financial transactions and account balances. If Spring Data JPA auditing fails to trigger, `createdAt` and `updatedAt` will be null in database rows.

#### Function 3.1: Automatically Populates `createdAt` and `updatedAt` on Persist
- **Missing Test**: Verifies that when a persistent entity extending `AuditableEntity` is saved, both fields are populated with UTC timestamps.
- **Snippet:**
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

        Thread.sleep(20); // Advance clock
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

## 4. Package: `com.academy.paybridge.shared.config`

### Class: `JpaAuditingConfig`
- **Source File:** [`JpaAuditingConfig.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/config/JpaAuditingConfig.java)
- **Target Test File:** `com.academy.paybridge.shared.config.JpaAuditingConfigTest` (New Test Class)
- **Current Status:** ⏳ **0 Tests (Total test debt)**

#### Function 4.1: UTC Clock Bean Verification
- **Missing Test**: Proves `clock()` bean is non-null and set to UTC timezone (`ZoneOffset.UTC`).
- **Why it matters:** Payments records must always be timestamped in UTC to prevent timezone skew across ledger lines and settlement reports.

#### Function 4.2: `auditingDateTimeProvider` Returns Current Instant
- **Missing Test**: Proves `auditingDateTimeProvider(Clock)` delegates directly to the provided `Clock`.
- **Snippet for `JpaAuditingConfigTest`:**
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
        Instant fixedInstant = Instant.parse("2026-10-05T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        DateTimeProvider provider = config.auditingDateTimeProvider(fixedClock);
        Optional<TemporalAccessor> now = provider.getNow();

        assertThat(now).isPresent();
        assertThat(now.get()).isEqualTo(fixedInstant);
    }
}
```

---

## 5. Package: `com.academy.paybridge.shared.exception`

### Class: `GlobalExceptionHandler`
- **Source File:** [`GlobalExceptionHandler.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/exception/GlobalExceptionHandler.java)
- **Target Test File:** [`GlobalExceptionHandlerTest.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/test/java/com/academy/paybridge/shared/exception/GlobalExceptionHandlerTest.java)

#### Function 5.1: Handles `CURRENCY_MISMATCH` with RFC 9457 HTTP 400
- **Missing Test**: Verify HTTP 400 Bad Request, RFC 9457 format, and `code: "CURRENCY_MISMATCH"`.
- **Snippet to add to `GlobalExceptionHandlerTest`:**
```java
    @Test
    void handlesCurrencyMismatchExceptionWithBadRequestAndProperCode() throws Exception {
        mockMvc.perform(get("/test/currency-mismatch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"))
                .andExpect(jsonPath("$.detail").value("Currency mismatch: NGN vs USD"));
    }
```
*(With test controller helper method:)*
```java
        @GetMapping("/test/currency-mismatch")
        String currencyMismatch() {
            throw new CurrencyMismatchException(
                    Currency.getInstance("NGN"), 
                    Currency.getInstance("USD")
            );
        }
```

---

#### Function 5.2: Direct Unit Tests for Fast Feedback
- **Missing Test**: Unit test the exception handler directly without starting MockMvc.
- **Snippet to add to `GlobalExceptionHandlerTest`:**
```java
    @Test
    void handlePayBridgeExceptionDirectUnitTest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        PayBridgeException ex = new PayBridgeException(ErrorCode.VALIDATION_FAILED, "Amount is required");

        org.springframework.http.ProblemDetail problem = handler.handlePayBridgeException(ex);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Amount is required");
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void handleUnexpectedExceptionDirectUnitTest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Exception ex = new RuntimeException("DB Connection Timeout");

        org.springframework.http.ProblemDetail problem = handler.handleUnexpected(ex);

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }
```

---

### Class: `ErrorCode`
- **Source File:** [`ErrorCode.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/shared/exception/ErrorCode.java)
- **Target Test File:** [`ErrorCodeTest.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/test/java/com/academy/paybridge/shared/exception/ErrorCodeTest.java)

#### Function 5.3: Name Invariance & Enum Parsing
- **Missing Test**: Verifying exact enum literal strings to guarantee contract stability across client integrations.
- **Snippet to add to `ErrorCodeTest`:**
```java
    @Test
    void allExpectedErrorCodesAreDefinedAndParseable() {
        assertThat(ErrorCode.valueOf("VALIDATION_FAILED")).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ErrorCode.valueOf("CURRENCY_MISMATCH")).isEqualTo(ErrorCode.CURRENCY_MISMATCH);
        assertThat(ErrorCode.valueOf("RESOURCE_NOT_FOUND")).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(ErrorCode.valueOf("CONFLICT")).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ErrorCode.valueOf("INTERNAL_ERROR")).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
```

---

## 6. Package: `com.academy.paybridge` (Core Application & Modularity)

### Class: `PayBridgeApplication`
- **Source File:** [`PayBridgeApplication.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/main/java/com/academy/paybridge/PayBridgeApplication.java)
- **Target Test File:** [`PayBridgeApplicationTests.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/test/java/com/academy/paybridge/PayBridgeApplicationTests.java)

#### Function 6.1: `main(String[] args)` Method Execution
- **Missing Test**: Verifying the `main` entrypoint can be executed cleanly.
- **Snippet:**
```java
    @Test
    void mainMethodLaunchesCleanly() {
        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() -> {
            // Can be launched with test arguments or spring profile if needed
        });
    }
```

---

### Class: `ModularityTests`
- **Source File:** [`ModularityTests.java`](file:///C:/Users/CHUKWUMA/projects/paybridge/paybridge/src/test/java/com/academy/paybridge/ModularityTests.java)

#### Function 6.2: Spring Modulith Architecture Documentation Export
- **Missing Test**: Generates PlantUML / C4 architectural documentation and module canvas automatically.
- **Why it matters:** Automatically documents the bounded contexts and detects unmapped module cross-dependencies early.
- **Snippet:**
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

## 7. Upcoming Modules: Test Blueprint Register (Step 3+)

As new modules are implemented in accordance with `BUILD-LOG.md`, append missing tests to this section using the same format:

```
### Package: `com.academy.paybridge.<module>.<layer>`
#### Class: `<ClassName>`
- Method: `<methodName>`
  - Status: ⏳ Missing
  - Why it matters: ...
  - Snippet: ...
```

### Pre-defined Module Test Templates:

| Module | Key Testing Scenarios to Capture When Written |
|---|---|
| **`customer`** | NUBAN customer generation, KYC tier verification, duplicate phone/email uniqueness |
| **`account`** | Account creation, status transitions (`ACTIVE`, `FROZEN`, `CLOSED`), pessimistic locking on balance updates |
| **`ledger`** | Double-entry journal balance invariant (sum of debits == sum of credits == 0), append-only constraint enforcement |
| **`transfer` (internal)** | Idempotency key deduplication (same key = same result, different payload = 422, in-flight = 409), lock ordering by account ID (deadlock prevention) |
| **`compliance`** | Per-tier daily & single-transaction limit enforcement |
| **`transfer` (external)** | Transactional outbox polling, Paystack API client WireMock tests, webhook HMAC verification, reversal processing |

---

## 8. Maintenance Workflow

To maintain this document:
1. When writing a new class or method, do **not** write tests directly in test suites if deferring test debt. Instead, create an entry here under its respective package and class.
2. Provide a fully written, runnable JUnit 5 / AssertJ test snippet.
3. When paying down test debt, transfer the code snippet from this file into the corresponding test class in `src/test/java` and mark the status here as ✅ Covered.
