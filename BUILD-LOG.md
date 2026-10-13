# PayBridge Build Log

A running record of **what we built, why we built it that way, and what every concept means.**
It is written for a beginner learning Java and Spring Boot by building a real payments backend.

> **How to read this document.** Every decision has a *Why* and, where it helps, an *Analogy*.
> If a term is new, look it up in the [Glossary](#12-glossary). Status markers:
> ✅ done · 🔄 in progress · ⏳ not started.

**Last updated:** 2026-10-13 · **Current position:** Steps 1 to 10 are built and verified (217 automated tests, plus live end-to-end runs), and every request is now access-logged with its request id. **Step 11 (the real Paystack gateway) is on hold** while the Paystack account is sorted out; see [section 15](#15-status-and-what-is-left-2026-10-13) for what is left.

---

## Contents

1. [The project in one page](#1-the-project-in-one-page)
2. [Scope decisions](#2-scope-decisions)
3. [Technology decisions](#3-technology-decisions)
4. [The architecture](#4-the-architecture)
5. [The core ideas of a payments system](#5-the-core-ideas-of-a-payments-system)
6. [The full build plan](#6-the-full-build-plan)
7. [Step 1 in detail: the project skeleton](#7-step-1-in-detail-the-project-skeleton)
8. [Problems we hit and how we solved them](#8-problems-we-hit-and-how-we-solved-them)
9. [Decision register](#9-decision-register)
10. [Testing strategy](#10-testing-strategy)
11. [What is next](#11-what-is-next)
12. [Glossary](#12-glossary)
13. [Steps 3 to 10: built in one pass](#13-steps-3-to-10-built-in-one-pass-2026-10-06)
14. [Request logging and the request-id fix](#14-request-logging-and-the-request-id-fix-2026-10-13)
15. [Status and what is left](#15-status-and-what-is-left-2026-10-13)
16. [Payment providers other than Paystack](#16-payment-providers-other-than-paystack-researched-2026-10-13)

---

## 1. The project in one page

**PayBridge** is a backend-only Spring Boot application that moves money. It was
specified by two documents that came with the assignment (`README.md` and `Sandbox.md`).

It eventually covers three flows:

| Flow | Meaning | Phase |
|---|---|---|
| Local transfer | Naira to naira, between accounts inside Nigeria | **Phase 1 (now)** |
| Cross-border settlement | Money settling between countries | Later |
| Remittance | USD received from abroad, paid out in NGN | Later |

**Phase 1 goal:** local NGN-to-NGN transfers, in two forms:

- **Internal transfer:** both accounts live inside PayBridge. No money leaves the system;
  only our own records change.
- **External transfer:** PayBridge pays out to a real Nigerian bank account, using
  **Paystack** (a payment company) in its free *test mode* so no real money moves.

> **Analogy: a school's pocket-money system.**
> An *internal* transfer is one pupil handing a coin to another, inside the school.
> An *external* transfer is the school posting a cheque to a pupil at a different school:
> it leaves the building, takes time, and might bounce.

---

## 2. Scope decisions

| # | Decision | Why |
|---|---|---|
| 1 | **Backend only.** No screens, no front end. | Money rules are the hard part. A UI would only hide mistakes. |
| 2 | **Internal transfers first, external second.** | Internal transfers force us to get the ledger, balances, locking and duplicate protection right. External transfers reuse all of that and add one hard thing: an unreliable outside company. One hard problem at a time. |
| 3 | **Fees on external transfers only.** | Paystack charges for payouts, so a fee is real there. Internal transfers stay free. The fee is built in Step 10. |
| 4 | **NUBAN-style account numbers.** | Nigerian bank numbers are 10 digits, the last being a *check digit* computed from the first nine plus a bank code. It teaches real banking validation. |
| 5 | **Write comprehensive tests.** | In payments, "it seemed to work" is not good enough. See [section 10](#10-testing-strategy). |
| 6 | **Defer `fx`, `remittance`, `settlement`, full AML.** | Nothing in local transfers needs them. Their folders stay as empty placeholders. |

---

## 3. Technology decisions

| Technology | Decision | Why |
|---|---|---|
| **Java 25** | Chosen by you | A long-term-support (LTS) release, so it gets fixes for years. |
| **Spring Boot 4.1.1** | The newest plain (non-SNAPSHOT) release offered | Boot 4 is a major version with reorganised starters. 4.1.1 is a final release, one patch into its line, which is the safest choice for a money project. |
| **Maven** | Chosen by you | The project already used it. We use the *Maven Wrapper* (`mvnw`) so everyone builds with the same Maven version. |
| **PostgreSQL** | Chosen by you | Real transactions (ACID), row locking and constraints, all of which money needs. |
| **Flyway** | Database schema is versioned in SQL files | The schema is reproducible and reviewable, like source code. |
| **Testcontainers** | Tests run against a real throwaway Postgres in Docker | An in-memory fake database behaves differently around locking and SQL, so concurrency tests on a fake would give false confidence. |
| **Spring Modulith** | Enforces the module rule | The README says modules may only talk through `api/`. A rule nobody checks is a wish. Modulith turns it into a failing test. |
| **Log4j2 (not Logback)** | Kept from the assignment's pom | A deliberate choice already in the project. We kept it and made sure nothing re-introduces Logback. |
| **YAML config** (`application.yml`) | Kept, not `.properties` | The project already used YAML, and nesting reads better as our settings grow. |
| **API keys for authentication** (built in Step 13) | Recommended over JWT | PayBridge has no human login. Other systems call it. API keys suit server-to-server traffic, and we store only a *hash* of each key. |

> **Analogy: choosing materials for a bank vault.**
> You don't pick the cheapest door. Postgres is the steel door (real guarantees).
> Flyway is the signed building plan (every change recorded). Testcontainers is
> testing the door on a real door, not a painting of one.

---

## 4. The architecture

### 4.1 A modular monolith

One application, deployed as one unit, but split internally into **modules** with strict
borders.

> **Analogy: an apartment building vs a street of houses.**
> A *microservice* system is a street of separate houses: each has its own front door,
> and visiting means walking outside. A *modular monolith* is one apartment building:
> separate flats with their own locked doors, one shared foundation and one set of keys
> for the building. You get privacy between flats without the cost of separate houses.

### 4.2 The modules

| Module | Responsibility | In Phase 1? |
|---|---|---|
| `shared` | `Money`, error handling, config, auditing | Yes |
| `customer` | Customer onboarding and KYC profile | Yes |
| `account` | Accounts and balances | Yes |
| `ledger` | Double-entry bookkeeping | Yes |
| `transfer` | Account-to-account transfers | Yes |
| `compliance` | Limits (thin version: transfer limits only) | Yes, thin |
| `notification` | Alerts through events (thin version: logs only) | Yes, thin |
| `fx`, `remittance`, `settlement` | Later phases | No |

### 4.3 The module rule

> *A module may only use another module through its `api/` package. Never call another
> module's repository or service directly.*

> **Analogy: the front desk.**
> Each flat has a reception desk (`api`). If you want something from flat 5, you ask at
> its desk. You don't walk into its kitchen (`repository`, `service`) and open the fridge.
> That lets flat 5 rearrange its kitchen any time without breaking anyone else.

### 4.4 Layout inside a module

Your assignment's layout is kept as given. Each module uses layers:

```
api/          the public front desk (other modules may use this)
domain/       the business objects and rules
repository/   talking to the database
service/      the use cases (the steps of a business action)
web/          REST endpoints (the HTTP front door)
client/       talking to outside companies (only in transfer; Paystack lives here)
```

We **added `transfer/client`** because the plan needs a home for the `TransferGateway`
(an interface) and its Paystack implementation. Nothing else in the layout was changed.

### 4.5 How modules talk

- **Direct call through `api/`:** when we need an answer now ("is this account active?").
- **Events:** when we are announcing that something happened ("a transfer completed").
  The `notification` module reacts without `transfer` knowing it exists.

> **Analogy:** a direct call is phoning someone and waiting for their answer. An event is
> a notice on the building's noticeboard. Whoever cares reads it, and the person who
> pinned it doesn't wait.

### 4.6 Allowed dependency direction

`transfer` may depend on `account`, `ledger` and `compliance`. They must **not**
depend back on `transfer`. A circle of dependencies is how modular monoliths slowly
turn into a ball of mud, and Modulith flags circles.

> **Analogy:** if flat A needs flat B and flat B needs flat A, neither can be rebuilt
> without the other. Keep the dependencies pointing one way, like a river.

---

## 5. The core ideas of a payments system

These are the ideas the whole design rests on. Each becomes real in a later step.

### 5.1 Money is whole numbers of kobo

Money is stored as an integer count of the smallest unit (**kobo**; 100 kobo = ₦1),
never as a decimal like `0.1`.

> **Why:** computers store decimals in binary, and `0.1` cannot be stored exactly.
> Add enough of them and pennies appear or vanish. **Analogy:** measuring with a ruler
> marked only in whole millimetres: you never argue about "0.30000000000000004 mm".
> Paystack also uses kobo, so there is no conversion bug waiting for us.

The `Money` value object (Step 2) holds an amount in kobo plus a currency, is
immutable, and refuses to add naira to dollars.

### 5.2 Double-entry ledger

Every movement of money is a **journal entry** made of **lines** that add up to zero.
If Ada sends ₦5,000 to Bola: Ada −5,000, Bola +5,000.

> **Analogy: a seesaw that must always balance.** Money never appears or disappears,
> it only moves. If a bug makes the seesaw tilt, an unbalanced entry exposes it at once.

Lines are **append-only**: never edited, never deleted. A mistake is fixed by posting a
*reversing* entry, like crossing out a line in a paper ledger and writing the correction
beneath, not tearing out the page.

### 5.3 Balance: derived vs cached

The true balance is the sum of ledger lines. Summing millions of rows on every read is
slow, so each account also stores a **cached balance** updated in the same database
transaction as the ledger lines. A check can later prove cache equals sum.

> **Analogy:** the sum of every receipt is the truth. The number on your banking app is a
> running total kept for speed. Occasionally you check it against the receipts.

### 5.4 Special internal accounts

External transfers need accounts that are not customers':

- **Suspense (clearing):** where money waits while a payout is in flight.
- **Paystack settlement:** money we have handed to Paystack.
- **Fees:** where fee income lands.

> **Analogy:** a posting counter. When you hand over a parcel, it isn't "gone" and it
> isn't "delivered": it is *in transit*. Suspense is the in-transit shelf.

### 5.5 Concurrency and locking

Two simultaneous ₦8,000 transfers from a ₦10,000 account must not both succeed. We lock
the account row while changing it (`SELECT … FOR UPDATE`, *pessimistic locking*) and
**always lock accounts in a fixed order (by ID)**.

> **Analogy: one till, two customers.** Without locking, both customers hear "you have
> ₦10,000" and both spend it. With locking, the second waits until the first finishes.
> The fixed order is a one-way corridor: if A→B and B→A grabbed their locks in opposite
> orders, they would stand in the doorway forever (a *deadlock*).

### 5.6 Idempotency

Networks retry. If "pay Bola ₦5,000" is sent twice because a response was lost, Bola
must be paid **once**. The client sends an `Idempotency-Key`, stored with a fingerprint
of the request under a **unique constraint** in the database.

- Same key, same body: return the first result.
- Same key, different body: reject (422).
- Same key while the first is still running: reject (409).

> **Analogy: a ticket stub.** Show the same stub twice and the cloakroom hands back the
> same coat. It doesn't produce a second coat.

### 5.7 Transfer state machine

A transfer is always in exactly one state, and only certain moves are legal.

`PENDING → PROCESSING → SUCCESS`, `PROCESSING → FAILED`, and for external transfers
`SUCCESS → REVERSED` (Paystack can reverse after saying yes). Illegal moves throw.

> **Analogy: a parcel's tracking page.** It can go *Packed → Shipped → Delivered*, but
> never *Delivered → Packed*.

### 5.8 External transfers: the transactional outbox

**Rule: never call Paystack inside a database transaction.**
A slow call would hold locks and a connection. Worse, Paystack might succeed while our
commit fails (money sent, no record) or the reverse (debit, no payout).

The pattern:

1. **Transaction 1 (fast):** validate, lock, post ledger lines moving funds to
   *suspense*, save the transfer as `PENDING`, write an *outbox* row saying "send this to
   Paystack". Commit.
2. **Dispatcher (outside any transaction):** reads outbox rows and calls Paystack with our
   unique reference.
3. **Result arrives** by **webhook** (primary) or **polling** (backup): success moves funds
   from suspense to the Paystack account; failure returns them to the customer.

An **unknown** outcome (timeout) is treated as *still processing*, never as failed,
because the money may have gone. The unique reference means a retry cannot pay twice.

> **Analogy: writing a letter vs posting it.** First you write "send ₦5,000 to Bola" in a
> sealed outbox tray while standing at your desk (fast, safe, recorded). A separate
> postman collects trays and delivers them. If the postman is unsure whether delivery
> happened, he doesn't assume it failed. He asks the recipient.

### 5.9 Webhooks

A **webhook** is Paystack calling *us* to say "that transfer succeeded/failed".

- Verify the HMAC-SHA512 signature against the **raw** body (re-serialising JSON changes
  the bytes and breaks the check).
- Respond 200 fast and **deduplicate** by event ID, because Paystack retries.
- Never trust the payload's amount alone; compare with our own record.

> **Analogy:** the courier knocking on your door with news. You check their ID badge
> (signature), and if they knock twice with the same news you don't act twice.

### 5.10 The `TransferGateway` interface

Our service talks to an **interface** with business-level methods (resolve account,
initiate, verify). `PaystackTransferGateway` is one implementation. A fake
implementation is used in tests.

> **Analogy: a power socket.** The plug shape is the interface. Your lamp doesn't care
> whether electricity came from solar or the grid. Swapping Paystack for Flutterwave is
> swapping the supply behind the socket.

Why a fake? Sandboxes are optimistic and always succeed instantly. A fake lets us force
timeouts, failures and reversals on demand.

---

## 6. The full build plan

| Step | What | Status |
|---|---|---|
| 1 | Project skeleton (build, config, database, foundation tests) | ✅ |
| 2 | `shared`: `Money`, error model, auditing, correlation IDs | ✅ (`MoneyTest` gaps still open) |
| 3 | `customer`: entity, KYC tier, API | ✅ |
| 4 | `account`: NUBAN numbers, balances, system accounts | ✅ |
| 5 | `ledger`: journal entries and lines, balanced-only posting | ✅ |
| 6 | Concurrency hardening (parallel-load tests) | ✅ |
| 7 | `transfer` (internal): validation, idempotency, endpoints | ✅ |
| 8 | `compliance` (thin): per-tier limits | ✅ |
| 9 | `notification` (thin): event listeners | ✅ |
| 10 | `transfer` (external) with a fake gateway, outbox, fees, reversals | ✅ |
| 11 | Paystack client, tested with WireMock | ⏸ on hold (needs a Paystack test account; see section 16 for backups) |
| 12 | Webhooks and reconciliation | ⏳ |
| 13 | Hardening: API-key authentication, rate limiting, logging, metrics | 🔄 request logging done (section 14); authentication, rate limiting, metrics ⏳ |

Each step ends with passing tests before the next one starts.

---

## 7. Step 1 in detail: the project skeleton

**Goal:** an empty-but-correct project that builds, starts against a real Postgres, and
checks module boundaries automatically.

> **Analogy:** laying the foundation and testing the plumbing before building any rooms.
> If the water doesn't run now, you don't want to discover it after the walls are up.

**Working location:** `C:\Users\CHUKWUMA\projects\paybridge\paybridge`
(the folder containing `pom.xml`). The package root is `com.academy.paybridge`.

### What the assignment gave us

- A `pom.xml` with Boot **4.1.0**, Java **21**, five temporary security overrides, and only
  three dependencies (Web, Log4j2, Test).
- The Maven Wrapper (Maven 3.9.9).
- All module folders with sub-packages and `.gitkeep` files.
- An `application.yml` with only the app name and port 8080.
- A `.gitignore`. The folder is **not** a Git repository yet.

### Decision: keep the assignment's layout

We keep it exactly as given (only adding `transfer/client`), because it is the assignment's
structure and changing it would create friction with whoever set the work.

### Item 1 ✅ Check the tools

| Check | Result |
|---|---|
| Java | 25.0.4 (LTS) |
| Maven Wrapper | Maven 3.9.9 running on Java 25 |
| Docker | Client and Server both 29.8.1 |
| `JAVA_HOME` | Not set. Works anyway through `PATH`. Recommended to set later. |

**Concepts**

- **JDK vs JRE.** The JRE can only *run* Java. The JDK can also *compile* it. We need the JDK.
  *Analogy: a JRE is a reader; a JDK is a reader who can also write.*
- **`JAVA_HOME`.** An environment variable naming which Java tools should use.
  *Analogy: a note on the fridge saying which key opens the shed.*
- **Maven Wrapper.** A script that fetches the exact Maven version the project pins.
  *Analogy: a recipe that ships with its own measuring cups, so everyone's cake matches.*

> **A confusing moment worth remembering.** In PowerShell, Java and Maven print warnings
> to a channel PowerShell shows in red as if it were an error. The words
> `WARNING: A restricted method...` are harmless noise on Java 25. The real result is the
> Maven version lines.

### Item 2 ✅ Upgrade Boot from 4.1.0 to 4.1.1

The Initializr offered 4.0.8, 4.0.9, 4.1.1, 4.1.2 and 4.2.0. **4.1.2 was marked SNAPSHOT**,
so we chose **4.1.1**.

**Rule:** only pick a version with no label after the number. `SNAPSHOT`, `M1` and `RC1`
all mean "not final".

**Concepts**

- **POM (`pom.xml`).** Maven's description of the project: its identity, its ingredients
  (dependencies), and how to build it.
- **Parent POM (`spring-boot-starter-parent`).** Your pom *inherits* from it. It sets
  compiler options and, importantly, **dependency management**: a tested list of library
  versions that work together. That is why most of your dependencies need no version number.
  *Analogy: a school uniform policy. You don't choose the shade of blue; the school did.*
- **Property overrides.** A property in your own pom beats the parent's value. Powerful but
  it makes you responsible for compatibility.
- **SNAPSHOT.** A moving, in-progress build. Fine as *your* project's own version label
  (`0.0.1-SNAPSHOT`). Not fine for the framework you depend on.

### Item 3 ✅ Java version 21 → 25

The `java.version` property feeds the compiler's *release* setting, which controls which
language features and library APIs your code may use.

### Item 4 ✅ Add dependencies

We used the Spring Initializr's **Explore** view as a *reference* and copied the dependency
blocks into the existing pom. We did **not** generate a new project, because the assignment's
folder is the one we must use.

**Dependencies added**

| Group | What it provides |
|---|---|
| `starter-validation` | Rejects bad input at the door |
| `starter-data-jpa` | Hibernate, Spring Data and the connection pool: database access |
| `starter-flyway` and `flyway-database-postgresql` | Versioned schema migrations for Postgres |
| `starter-actuator` | Health checks |
| `postgresql` (runtime) | The JDBC driver that talks to Postgres |
| `spring-modulith-*` | Module checks, event publication, observability |
| `spring-boot-docker-compose` (runtime, optional) | Boot starts our database from a compose file automatically |
| `*-test` starters, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql` | Test support and real-Postgres tests |

We did **not** add Lombok (it hides what code really does) or Spring Security (it would
lock every endpoint now; we add it in Step 13).

**Concepts**

- **Starter.** A bundle that pulls in everything for a purpose.
  *Analogy: a meal kit. One order brings every ingredient for the recipe.*
- **Scope.** `test` means "only while running tests, not shipped". `runtime` means "needed to
  run but not to compile".
- **BOM (bill of materials).** A pom holding a matching version list. Importing it into
  `<dependencyManagement>` tells Maven "use these versions".
  *Analogy: a parts list from the manufacturer: "these gearbox parts all fit each other".*
- **Exclusion.** Removing one thing a dependency would otherwise bring along. We used it to
  keep Logback out (below).

#### The two tweaks needed after copying

1. **The Modulith BOM.** Boot's parent manages Boot's libraries but **not** Modulith's.
   Without a version for Modulith, the build failed with seven
   `dependencies.dependency.version ... is missing` errors. The fix was a version property
   `spring-modulith.version` (2.1.1, taken from the Initializr's own output) plus a
   `<dependencyManagement>` section importing `spring-modulith-bom`.
2. **Restore the three originals.** While pasting, the original `starter-web`,
   `starter-log4j2` and `starter-test` blocks were overwritten. Without `starter-web` there
   was no web server at all. They were restored exactly as they were.

#### Logging: why the exclusion repeats

The project deliberately uses **Log4j2**. Every Boot starter, however, depends on a base
starter that brings **Logback** (a competing logging engine). Two logging engines at
startup is a classic failure.

Maven has no "exclude this everywhere" switch, so the same exclusion
(`spring-boot-starter-logging`) is written on each dependency that brings Logback in:
`web`, `test`, `actuator`, `data-jpa`, `flyway`, `validation`. We proved it worked with:

```powershell
.\mvnw.cmd dependency:tree "-Dincludes=ch.qos.logback"
```

An empty result means Logback is gone.

> **Analogy:** two people both trying to drive the same car. You must remove the second
> set of keys from every drawer, and Maven can't do that in one move, so you check each
> drawer and then search the house to prove none are left.

#### Security versions

The original pom's five overrides came from a Mend scan on 2026-10-01 and said they could
be removed on 4.1.1. **That was only partly true.** After upgrading, the dependency tree showed:

| Library | Required | On 4.1.1 by default | Action |
|---|---|---|---|
| Spring Framework | 7.0.9 | 7.0.9 | none needed |
| Log4j2 | 2.25.5 | 2.25.5 | none needed |
| Tomcat | 11.0.26 | **11.0.24** | **override re-added** |
| Jackson | 3.1.7 | **3.1.5** | **override re-added** |

**Decision (Option A):** re-add only the two properties that were needed:
`tomcat.version` = 11.0.26 and `jackson-bom.version` = 3.1.7, with a comment explaining why
and when to remove them. After that, the tree showed all four libraries at or above the
minimums.

**Lesson:** *verify, don't trust a comment.* A comment can be out of date. The dependency tree
tells you what Maven actually resolved.

> **Analogy: a smoke alarm with a sticky note saying "battery replaced".** Press the test
> button anyway.

### Item 5 ✅ Modulith markers

**The problem.** Modulith's default rule treats a module's *root* package as public and every
*sub-package* as internal. Our public face is a sub-package (`api`). Without markers, Modulith
would call every legitimate use of `account.api` a violation.

**The fix.** Mark each `api` package as a public interface using an annotation placed in a
file named `package-info.java`.

**Modules to mark (in Phase 1):** `account`, `customer`, `ledger`, `compliance`, `transfer`.

**`shared`** is declared an **open** module instead, because every module needs its
`Money`, exceptions and auditing classes.

**Concepts**

- **Annotation.** A label attached to code with `@`. It doesn't change what the code does;
  tools read it and act on it. *Analogy: a "FRAGILE" sticker. It doesn't change the box, but
  couriers handle it differently.*
- **`package-info.java`.** A file that exists only to carry annotations for a whole package.
  *Analogy: the nameplate on a door, not a piece of furniture inside the room.*
- **Closed vs open module.** A closed module hides its internals (a private flat). An open
  module exposes everything (the building's lobby).

**What the files look like**

For `account/api/package-info.java` (change only the package line for the other four):

```java
@NamedInterface("api")
package com.academy.paybridge.account.api;

import org.springframework.modulith.NamedInterface;
```

For `shared/package-info.java` (the `shared` root folder):

```java
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.academy.paybridge.shared;

import org.springframework.modulith.ApplicationModule;
```

Order matters: annotation, then `package`, then imports.

**Why only these modules.** `fx`, `remittance`, `settlement` and `notification` have empty
packages. Modulith ignores a package with no classes, so we add markers when those modules get code.

**Status:** ✅ Done. Six files exist and `mvnw compile` passes: five `@NamedInterface("api")`
files (`account`, `customer`, `ledger`, `compliance`, `transfer`) and one `@ApplicationModule(type = OPEN)`
file in `shared`.

**Mistakes worth remembering.**
- IntelliJ's *New → Java Class* rejects the name `package-info` because a hyphen isn't legal in a class
  name. Use *New → File* instead.
- The first attempt had bare `package` lines with no annotation, and three files in modules outside
  Phase 1 (`fx`, `notification`, `remittance`). A bare `package` line does nothing, so the annotation
  and import are what make the file work. The three extra files were deleted.

### Items 6 to 11 ✅ Completed

**Outcome of Step 1:** `mvnw verify` green (2 tests), the app starts against a Postgres container,
Flyway applies `V1__init.sql` (which creates Modulith's `event_publication` table), `/actuator/health`
reports UP, and the first Git commit (`cbdee06`) is made. Compose maps the database to host port
**5433** (see problem 9 below). Graceful shutdown is on by default in Boot 4.1.1
(`server.shutdown=graceful`, 30 s timeout per phase).

The original plan for these items is kept below as a record of the reasoning.

| Item | What | Why |
|---|---|---|
| 6 | Extend `application.yml` | Set JPA to `validate`, turn off `open-in-view`, expose only `health` |
| 7 | Create `compose.yaml` (Postgres 17) | Boot starts a real database for local runs |
| 8 | Create `V1__init.sql` | First migration. **It must create Modulith's `event_publication` table** (see below) |
| 9 | Two foundation tests | Context loads on real Postgres, and Modulith verifies the structure |
| 10 | `mvnw verify`, then run and check `/actuator/health` | Prove the whole chain works |
| 11 | `git init`, update `.gitignore`, first commit | History starts at a known-good point |

**Key decisions in these items**

- **`ddl-auto: validate`.** On startup, Hibernate *checks* the tables match the entities and
  refuses to start if they don't. It never creates or changes tables. *Analogy: an inspector
  who checks the building against the plan but never lays a brick.* Flyway owns the schema.
- **`open-in-view: false`.** Spring would otherwise keep a database session open for the whole
  web request, even while building the response. That hides lazy-loading mistakes and holds
  connections longer. Turning it off makes us load what we need inside the service layer.
- **No datasource URL in `application.yml`.** Docker Compose Support reads the host, port and
  credentials straight from the running container. Fewer settings, fewer places for secrets.
- **Postgres pinned to `postgres:17`.** `latest` can change under you and break things.
- **Migrations are immutable.** Once a migration has run anywhere, never edit it. Flyway
  records a checksum and fails if the file changed. Fix mistakes with a *new* migration.
  *Analogy: you don't rewrite the minutes of last month's meeting; you add a new entry.*
- **The `event_publication` trap.** `spring-modulith-starter-jpa` stores unprocessed events in a
  table of that name, and registers a JPA entity for it. With `validate` on and no such table,
  the app won't start. So `V1__init.sql` must create it. We will use the schema Modulith ships,
  and verify it against the Modulith version.
- **Git hygiene.** `.gitignore` gains `.env` and `HELP.md`. Read `git status` *before*
  `git add .` to be sure no `target/`, IDE files or secrets sneak in.

---

## 7b. Step 2 in detail: the `shared` module 🔄

`shared` is the building's utility room: things every module needs and that know nothing about
customers, accounts or transfers. Sub-steps: **2.1** `Money` · **2.2** error vocabulary ·
**2.3** global error handler · **2.4** auditing fields · **2.5** request-ID (correlation) filter.

### 2.1 `Money` ✅ (tests incomplete, see open item)

`Money` is an immutable `record` of `long minorUnits` (kobo) and a `java.util.Currency`.
It can be negative (ledger lines are signed), refuses to mix currencies, and uses
`Math.addExact` / `subtractExact` / `negateExact` so overflow throws instead of silently wrapping.

> **Analogy:** a cheque. An amount and a currency, sealed together. Corrections are a new cheque,
> never an edit.

**Review findings, both fixed:** the null-check message said "must be null" (it must say
"must not be null"), and a method was named `Zero` instead of `zero` (Java methods start lowercase).

**Open item:** `MoneyTest` has 9 tests. Still to write: normal `negate`, the sign checks,
equality and `hashCode`, `compareTo` ordering, currency mismatch for `subtract` and `compareTo`,
and immutability of the originals after `add` and `subtract`.

### 2.2 Error vocabulary ✅

`ErrorCode` (enum of stable machine-readable codes, each tied to an HTTP status),
`PayBridgeException` (base `RuntimeException` carrying an `ErrorCode`), and `CurrencyMismatchException`
re-parented under it. Codes so far: `VALIDATION_FAILED`, `CURRENCY_MISMATCH`, `RESOURCE_NOT_FOUND`,
`CONFLICT`, `INTERNAL_ERROR`. New codes are added only when a module needs them.

> **Analogy:** a hospital wristband. The colour tag (code) is for machines, the written note
> (message) is for people. Fixing a typo in the note must never break a client, so clients read the code.

Tests: `ErrorCodeTest` (10), `PayBridgeExceptionTest` (3), `CurrencyMismatchExceptionTest` (4).
A test caught a real inconsistency here: the message began with a lowercase `c` in code but the test
expected a capital. Fixed in code.

### 2.3 Global error handler ✅

`GlobalExceptionHandler` (`@RestControllerAdvice`, extends `ResponseEntityExceptionHandler`) turns every
`PayBridgeException` into an RFC 9457 `ProblemDetail` with the HTTP status from its code and a `code`
property, and turns anything unexpected into a generic 500 whose real cause is only logged.
Tested with standalone MockMvc (3 tests), including a test that a secret in an exception message never
reaches the caller. Field-level validation errors are deferred to Step 3, when `customer` gets its first
validated request.

### 2.4 Auditing fields ✅

`AuditableEntity` (`@MappedSuperclass` with `createdAt` and `updatedAt`, stamped automatically by Spring
Data) in `shared/audit`, and `JpaAuditingConfig` in `shared/config` which switches auditing on and supplies
a `Clock` bean. Timestamps use `Instant` (one exact moment, no zone ambiguity). The stamp reads from the
injected `Clock`, so tests can freeze time later. No `createdBy` yet: that needs to know *who* is calling,
which arrives with API-key authentication in Step 13. Nothing is stamped until the first entity extends the
class (`Customer`, Step 3), whose migration must add `created_at` and `updated_at` as
`TIMESTAMP WITH TIME ZONE NOT NULL`.

> **Analogy:** a date stamp built into the filing cabinet, so no clerk can forget to date a paper.

### 2.5 Request-ID filter ✅

`RequestIdFilter` gives every request a "luggage tag": the caller's `X-Request-Id` if it matches
`[A-Za-z0-9._-]{1,64}` (anything else could be used for log injection), otherwise a random UUID. The ID goes
into the SLF4J MDC (a per-thread notepad), is echoed in the response header, and is removed in `finally`
because server threads are reused. `logging.pattern.correlation: "[%X{requestId:-}] "` prints it on log lines.

**Verified live** (real app, real Tomcat): no header gives a fresh UUID back; `my-test-123` is echoed; an ID
containing spaces is replaced; the header is present on a 404 too.

**Problem found by running the app, not by the tests:** with `@Component` the filter crashed startup
(`NullPointerException ... "this.logger" is null`). Cause: Modulith's observability support
(`ModuleObservabilityBeanPostProcessor`) wraps beans in module packages in CGLIB proxies, and a proxy of
`GenericFilterBean` skips its constructor while its `init` method is `final`. Fix: do not make the filter a
bean. Register it with a `FilterRegistrationBean` (`RequestIdFilterConfig`). **Rule for later:** any servlet
filter in a module (e.g. the API-key filter, Step 13) is registered this way.
`PayBridgeApplicationTests` missed it because it uses a mock web environment and never starts Tomcat.

**Follow-ups, since closed:** business-error responses now carry the `requestId` field. Whether the ID appeared on log
lines *during* a request stayed unproven until the access log was added (2026-10-13), which showed it **never did**:
the correlation pattern was in Logback syntax. Fixed; see section 14.

### Testing decision: tests deferred from here on (recorded 2026-10-05)

To move faster, we agreed to **stop writing new tests for now** and only check that new code compiles and
that the existing 31 tests still pass. **This is test debt.** It must be paid back before Step 6 (concurrency)
and Step 7 (transfers), where money correctness depends on it. Debt so far: the missing `MoneyTest` groups
above, and tests for everything from 2.4 onward.

**Update (2026-10-13): the debt is paid except `MoneyTest`.** Steps 3 to 10 were written with tests, and the suite is now
**217 tests**. Still open: `negate`, the sign checks, equality and `hashCode`, `compareTo` ordering, currency mismatch on
`subtract` and `compareTo`, and immutability of the originals.

---

## 8. Problems we hit and how we solved them

A log of real stumbling blocks is the most valuable part of a learning record.

| # | Problem | Cause | Fix | Lesson |
|---|---|---|---|---|
| 1 | I described files in the wrong folder | The Markdown files were one level deeper than I first looked | Re-listed the folder and found the real project | Look at what is really there before planning |
| 2 | The Initializr plan assumed a new project | The assignment already supplied a project | Adapted the plan to adopt the supplied folder | Fit the plan to the situation, not the reverse |
| 3 | `mvnw.cmd -v` appeared to fail | PowerShell shows harmless Java warnings in red | Read the version lines, not the colour. It worked. | Red text isn't always an error |
| 4 | Build failed: "version is missing" ×7 | Modulith's versions aren't managed by Boot, and the BOM was absent | Added the version property and BOM | Libraries outside Boot's list need their own BOM |
| 5 | `starter-web`, `log4j2`, `test` disappeared | The pasted block replaced the original dependencies | Restored them exactly | When adding, don't replace. Diff before saving |
| 6 | Logback reappeared | Every new starter brings it | Added the exclusion to each, then proved it with `dependency:tree` | Prove it with a tool, don't assume |
| 7 | Tomcat and Jackson were too old | The "remove on 4.1.1" comment was wrong for these two | Re-added two overrides (Option A) | Verify claims, even written ones |
| 9 | First `spring-boot:run` failed with `password authentication failed for user "paybridge"` | A Windows PostgreSQL 18 service already owned port 5432, so the app logged into *that* server, not our container | Mapped the container to host port 5433 (`"5433:5432"`) in `compose.yaml` | The error message names the symptom, not always the culprit. Check *which* server answered |
| 8 | 4.1.2 was labelled stable by mistake | I assumed the label without seeing the Initializr | Used 4.1.1, the plain release | Believe the screen over an assumption |
| 10 | App crashed at startup with `NullPointerException ... "this.logger" is null` after adding the request-id filter | The filter was a `@Component`; Modulith's observability wraps module beans in CGLIB proxies, which skip the constructor | Register the filter with a `FilterRegistrationBean` (the access-log filter follows the same rule) | Tests with a mock web environment never start Tomcat. Run the real app |
| 11 | `AccountResponse` was an empty placeholder (`public record AccountResponse() {}`) | A stub left from earlier | Replaced with the full record | Read the file, don't trust its name |
| 12 | IntelliJ: "package-info is not a valid qualified name" | *New → Java Class* rejects hyphens | Use *New → File* | The tool's rules are not Java's rules |
| 13 | The "all balances sum to zero" test failed intermittently | An account test changed a balance directly and left it changed, polluting the shared database | That test now zeroes the balance at the end | Tests sharing one database must clean up after themselves |
| 14 | Docker Desktop engine stopped or "pipe busy" during test runs | Docker not fully started | Restart Docker Desktop and retry | Testcontainers and compose both need a healthy engine |
| 15 | 124 tests errored with "ApplicationContext failure threshold exceeded" (2026-10-13) | Docker Desktop was not running, so Testcontainers could not start a database. The new filter tests, which need no Docker, passed | Started Docker and reran: 217 pass | A wall of identical context errors usually means one environment problem, not 124 code problems. Read the first cause |
| 16 | The request id never appeared on any log line (`[]`) although the response header had it | The pattern `%X{requestId:-}` is Logback syntax. Log4j2 reads `requestId:-` as the key name, finds nothing and prints blank | Changed the pattern to the Log4j2 form `[%X{requestId}] ` and proved it against a real running app | The same words mean different things in different logging engines. Check the output, not just that the setting loads |
| 17 | `Port 8080 was already in use` when starting the app | An earlier `spring-boot:run` (or IntelliJ run) was still running | Found it with `netstat -ano \| grep 8080` and stopped it with `taskkill //PID <pid> //F` (Git Bash needs the double slash) | The startup log reaches Tomcat before this fails, so everything else is fine |

---

## 9. Decision register

| Area | Decision | Alternative rejected | Reason |
|---|---|---|---|
| Scope | Internal transfers first, then external | External first | Face one hard problem at a time |
| Scope | Fees on external only | Fees everywhere | Paystack charges for payouts; internal moves cost nothing |
| Accounts | NUBAN-style numbers | Simple sequential numbers | Teaches real banking validation |
| Build | Maven (via wrapper) | Gradle | Project already used Maven |
| Boot | 4.1.1 | 4.1.2, 4.2.0, 4.0.x | 4.1.2 was SNAPSHOT. 4.2.0 is a first-of-its-line release. 4.0.x is further from current. |
| Java | 25 | 21 | Your decision; LTS |
| Layout | Keep the assignment's layout, add `transfer/client` | Reshape to `internal/` | Avoid friction with the assignment |
| Logging | Log4j2 with Logback excluded everywhere | Switch to Logback | A deliberate choice already in the project |
| Config format | YAML | `.properties` | Already in use; nesting reads better |
| Security versions | Re-add Tomcat and Jackson overrides | Leave older versions | The assignment's own scan flagged them |
| Auth | API keys (Step 13) | JWT | No human users; server-to-server |
| Modulith | `@NamedInterface("api")` on `api` packages, `shared` open | Rename `api` to the module root | Keeps the assignment's layout |
| Database tests | Testcontainers Postgres | H2 | A fake database behaves differently |
| Working method | Build the finished system in this copy, with a frozen hand-built snapshot in the learning copy | Hand-build every step in the 36-hour window | A hard demo deadline |
| Ledger | Append-only, enforced by database triggers as well as code | Code checks only | A bug in one layer must not corrupt money |
| Idempotency | `Idempotency-Key` header, `INSERT ... ON CONFLICT DO NOTHING`, SHA-256 fingerprint | Check-then-insert in code | A unique constraint is the only race-free check |
| Compliance limits | One atomic SQL upsert on `daily_usage` | Read, check, write | Two simultaneous transfers could both pass a read-then-write check |
| External fee | Charged on top of the amount, banded, held with the amount | Deducted from the amount | The recipient gets exactly what was typed; the hold covers amount plus fee |
| External transfers | Accept in one transaction, call the bank outside any transaction (outbox plus dispatcher) | Call the bank inside the transaction | Slow, unreliable calls must not hold locks or risk paying without a record |
| Timeouts | A timeout is "unknown": keep the money held, retry, then ask the bank | Treat a timeout as a failure and refund | Refunding early can pay the customer and the bank both |
| Gateway | `TransferGateway` interface with a fake bank first | Go straight to Paystack | Sandboxes always say yes; the fake can force timeouts, refusals and reversals |
| Events | Modulith `@ApplicationModuleListener` (after commit, asynchronous, persisted) | Call notification code directly | A failed alert can never undo a transfer |
| Request logging | One access-log line per request from a servlet filter, bodies and headers never logged | Log inside each controller; log bodies for debugging | One place cannot be forgotten; bodies will hold personal data and (Step 13) API keys |
| Health-check logging | DEBUG when it succeeds, ERROR when it fails | Log every poll at INFO | Pollers hit it every few seconds and would bury real traffic |
| Step 11 | Put on hold until a Paystack test account exists | Block everything on it | The rest of the system does not depend on which bank is behind the interface |

---

## 10. Testing strategy

| Layer | Tool | What it proves |
|---|---|---|
| Unit | JUnit 5, AssertJ | Pure logic: `Money`, state machine, validation |
| Repository / slice | Testcontainers Postgres | Queries, constraints and migrations really work |
| Module integration | Spring Boot test | A module works through its own `api` |
| Architecture | Modulith verify (and ArchUnit) | Boundaries and cycles are respected |
| Concurrency | Parallel executors | No overdraft, no deadlock, money conserved |
| Gateway | WireMock | The Paystack client handles good and bad responses |
| Webhook | Spring MVC test | Signature checks, duplicates, out-of-order events |
| End to end | Full app, fake gateway | Complete flows through HTTP |
| Failure injection | Fake gateway modes | Timeouts, unknown outcomes, reversal after success |

Rules: tests are deterministic (an injectable clock, no sleeping); each test starts from clean
data; failure cases come first for money-critical code.

**Where we actually are (2026-10-13): 217 tests, 0 failures, 0 skipped.** In practice:

- **Unit tests:** `Money`, `Nuban`, error codes and exceptions, the transfer state machine, fee bands, and
  `AccessLogFilterTest` (9 tests, no Docker needed: it listens to the filter's real logger and checks each log level).
- **Whole-application tests** extend `AbstractIntegrationTest` (a real Postgres through Testcontainers, `MockMvc` through
  `webAppContextSetup`, helpers `createCustomer`, `openAccount`, `newAccount`, `fundAccount`, `newFundedAccount`). They share
  one database, so each test creates its own customers and accounts. **They need Docker running.**
- **`application-test.yml`** switches the dispatcher and reconciler timers off and makes retries instant, so tests drive them
  step by step.
- **Concurrency tests** use a `CountDownLatch` "starting gun" and check the invariant that **all balances sum to zero**.
- **External-transfer tests** (`ExternalTransferTest`, 28; `BeneficiaryTest`, 8) drive the fake bank's behaviours: timeouts,
  refusals, slow success, reversal after success, and late success after a refund.
- **`ModularityTests`** is the Modulith structure check.
- **Not yet written:** mock-server tests for the Paystack client (Step 11) and webhook tests (Step 12).

**Note on `MockMvc`:** the shared `MockMvc` is built with `webAppContextSetup(...).build()`, which does **not** add servlet filters.
So the request-id and access-log filters are tested on their own, and were also proved against a real running app.

> **Analogy: crash-testing a car.** You don't only drive it on a sunny day. You hit it into
> walls on purpose, because the failure behaviour is what protects people.

---

## 11. What is next

Steps 1 to 10 are done. The live list of what is left, in priority order, is in
[section 15](#15-status-and-what-is-left-2026-10-13).

**Standing to-dos**

- Optionally set `JAVA_HOME` to `C:\Program Files\Java\jdk-25.0.4`, then reopen the terminal.
- When Step 11 resumes: use a Paystack **test-mode** secret key (starts with `sk_test_`), never commit it, and pass it as an
  environment variable. If a Paystack account is slow to get, section 16 lists backups.

---

## 12. Glossary

| Term | Meaning | Analogy |
|---|---|---|
| **ACID** | Database guarantees: Atomic, Consistent, Isolated, Durable | A bank teller who either finishes your whole withdrawal or none of it |
| **Annotation** | A `@Label` attached to code that tools read | A "FRAGILE" sticker |
| **API (`api/`)** | The public surface a module offers others | A reception desk |
| **Append-only** | Records are added, never changed or deleted | A diary written in pen |
| **BOM** | A pom listing matching library versions | A manufacturer's parts list |
| **Check digit** | A final digit computed from the others to catch typos | The last digit of a credit card |
| **Circular dependency** | A depends on B depends on A | Two people each waiting for the other to move first |
| **Concurrency** | Many things happening at once | Two customers at one till |
| **Dependency** | A library your code needs | An ingredient |
| **Dependency management** | A tested list of compatible library versions | A school uniform policy |
| **Deadlock** | Two actions each waiting for the other's lock | Two cars nose-to-nose in a one-lane street |
| **Double-entry** | Every change is recorded as balancing debits and credits | A seesaw that must balance |
| **Event** | A notice that something happened | A noticeboard pin |
| **Exclusion** | Removing something a dependency would bring | Taking the nuts out of a trail mix |
| **Flyway** | Tool that applies versioned SQL files to the database | A signed building-plan register |
| **GA / stable release** | A final release with no `SNAPSHOT`, `M` or `RC` label | A published book vs a draft |
| **HMAC signature** | A tamper-proof stamp computed from a secret | A wax seal |
| **Idempotency** | Doing the same request twice has the same effect as once | A ticket stub for the cloakroom |
| **Interface (Java)** | A promise of methods without saying how | A power socket's shape |
| **JDK / JRE** | Java Development Kit / Runtime Environment | Writer-and-reader / reader only |
| **KYC** | Know Your Customer: identity verification | Showing ID at a bank |
| **Kobo** | One hundredth of a naira | Cents |
| **Ledger** | The permanent record of all money movements | A paper accounting book |
| **Maven Wrapper** | Script that fetches the pinned Maven version | A recipe with its own measuring cups |
| **Migration** | A versioned SQL change to the schema | A dated entry in a logbook |
| **Modular monolith** | One deployable app with strict internal module borders | An apartment building |
| **Module** | A self-contained slice of the application | A flat in the building |
| **Modulith** | Library that checks module boundaries | A building inspector |
| **NUBAN** | Nigeria's 10-digit bank account number standard | A standard phone-number format |
| **Optimistic / pessimistic locking** | Hope nobody clashes and check afterwards / lock first | Writing in a shared notebook vs reserving it |
| **Outbox** | Records "to send" in the same transaction, sent later | A tray of letters for the postman |
| **POM** | Maven's project description file | A project's ID card and ingredient list |
| **Property (Maven)** | A named value, such as a version | A label on a jar |
| **Reconciliation** | Comparing two records to ensure they agree | Checking receipts against your banking app |
| **Reversal** | A new entry that undoes an earlier one | A correction slip, not an eraser |
| **Scope (Maven)** | When a dependency is available: compile, runtime, test | Which rooms a tool may be used in |
| **Service connection** | Spring auto-wiring a connection to a container | Autofilling a form |
| **SNAPSHOT** | An in-progress, changing build | A draft |
| **Starter** | A bundle of dependencies for one purpose | A meal kit |
| **State machine** | Fixed states with only legal transitions | A parcel's tracking page |
| **Suspense account** | Internal account holding money in transit | The in-transit shelf |
| **Testcontainers** | Library that starts throwaway Docker services for tests | A disposable test lab |
| **Transaction** | A group of database changes that all happen or none do | Paying and receiving a receipt as one act |
| **Unique constraint** | The database refuses duplicates in a column | A seat number that can only be sold once |
| **Webhook** | An outside system calling *us* with news | A courier knocking on the door |
| **YAML** | A nested, indentation-based config format | An outline with indents |
| **Access log** | One line per request: method, path, status, time | A visitors' book at the front desk |
| **Correlation (request) id** | A unique id attached to every log line of one request, returned in the `X-Request-Id` header | A luggage tag |
| **MDC** | Mapped Diagnostic Context: a per-thread notepad the logging engine reads when writing a line | A sticky note on the desk of whoever is working |
| **Servlet filter** | Code that wraps every web request before and after the controller | A security gate everyone walks through |

---

*This document is updated as we complete each item. If a decision changes, we record the
change and the reason, and never silently overwrite history.*

---

## 13. Steps 3 to 10: built in one pass (2026-10-06)

To save time, Claude wrote Steps 3 to 10 in one go, checking each step with tests and, at the end, a live run of the real app.
A frozen snapshot of the hand-built work (Steps 1 to 2 and the start of 3) is in the sibling folder `paybridge(learning copy)`.

**Result (as of 2026-10-06):** 208 automated tests passed (217 today, after the access-log work in section 14). A live run against a throwaway Postgres confirmed: customer and account creation, funding,
an internal transfer and its idempotent replay, validation errors, saved payees, an external transfer that the timers finished on
their own (success), one the fake bank refused (automatic refund), SMS/email alerts, and the limits endpoint.

### What exists now

| Step | Built | Key decisions and why |
|---|---|---|
| 3 `customer` | Entity, `CustomerApi`, register / get / change KYC tier, field-level validation errors | `KycTier` and `CustomerStatus` live in `api/` because other modules read them. Emails stored lower-case. No BVN/NIN stored (sensitive). |
| 4 `account` | NUBAN accounts (9-digit serial from a sequence plus check digit), system accounts (FUNDING_SOURCE, SUSPENSE, PAYSTACK_SETTLEMENT, FEES), balance cache | Customer balance can never be negative: enforced in code AND by a database CHECK. `lockAccounts` always locks in id order. |
| 5 `ledger` | Journal entries and lines, `post` / `reverse` / statement | Posting checks everything before changing anything. The same (type, reference) never posts twice. **Postgres triggers** reject unbalanced entries at commit and any UPDATE/DELETE of ledger rows. |
| 6 concurrency | Parallel-load tests | 40 simultaneous transfers against a NGN 100 balance: exactly 33 succeed. 200 opposite-direction transfers: no deadlock. |
| 7 `transfer` internal | State machine, `Idempotency-Key`, `TransferCompleted` event, endpoints, dev-only funding endpoint | Key claimed with `INSERT ... ON CONFLICT DO NOTHING`, so simultaneous duplicates cannot both win. A refused request rolls back everything, including the key. |
| 8 `compliance` | Per-transaction and daily limits by tier, atomic reserve/release | One SQL upsert with a WHERE clause makes "check and count" a single atomic step. Days are Africa/Lagos days. |
| 9 `notification` | Listeners for completed / failed / reversed transfers, alert history endpoint | `@ApplicationModuleListener`: runs after commit, event kept on record until handled. Unique constraint stops duplicate alerts on redelivery. |
| 10 `transfer` external | Payees (name enquiry), fee bands, hold in SUSPENSE, outbox, dispatcher, reconciler, reversal handling, `TransferGateway` + simulated bank | See below. |

### How an external transfer works (the part to understand)

1. **Accept (one fast transaction, no outside call):** customer is debited amount + fee into SUSPENSE ("in transit shelf"), transfer saved as PENDING, an outbox row is written.
2. **Dispatcher (no transaction open):** marks PROCESSING, calls the gateway. SUCCESS/FAILED are applied at once. PENDING is recorded and left for later.
   **A timeout is never a failure**: it means unknown, so the money stays held and the call is retried (the reference makes the retry safe).
3. **Reconciler:** asks the provider what happened to quiet transfers. SUCCESS pays out (SUSPENSE to PAYSTACK_SETTLEMENT, fee to FEES);
   FAILED reverses the hold; "never heard of it" means it was never sent, so refund; PENDING means keep waiting.
4. **Reversal after success:** both the settlement and the hold are reversed, so the customer gets amount + fee back.
5. Every provider answer is applied **idempotently**, so a repeated webhook or poll changes nothing. A late "success" for an already-refunded transfer
   raises a "MANUAL REVIEW" error and moves no money.

The simulated bank picks its behaviour from the last four digits of the payee's account number (1111 refuse, 2222 timeout before receipt,
3333 instant success, 4444 pending then fail, 5555 timeout after receipt, 6666 pending forever, 7777 success then reversed, 9999 unresolvable).

### Things to know

- **Placeholders to check before trusting:** the fee bands (NGN 10 / 25 / 50) and the per-tier limits are made-up figures in `application.yml`;
  the bank code `999` is fictional; Paystack's real fees and minimums must be checked.
- **Dev-only endpoint:** `POST /api/dev/accounts/{id}/fund` puts test money in an account. Switch off with `paybridge.dev.funding-enabled=false`.
- **No authentication yet** (Step 13).
- **Known small gaps:** the request id is not copied onto the async notification threads (their log lines show `[]`); Spring logs a harmless
  warning that Modulith's observability wraps `GlobalExceptionHandler` in a proxy; `MoneyTest` still has the gaps listed in section 7b.
- **Tests:** a `test` profile (`src/test/resources/application-test.yml`) switches the dispatcher and reconciler timers off so tests drive them step by step.
- **Lesson repeated:** `PayBridgeApplicationTests` and the other tests use a mock web layer. Starting the real app caught nothing new this time, which is the point of doing it.

### Next: Step 11 (guided), currently on hold

Write `PaystackTransferGateway` implementing `TransferGateway`, selected with `paybridge.gateway=paystack`. It is on hold until a Paystack
test account exists; see sections 15 and 16.

---

## 14. Request logging and the request-id fix (2026-10-13)

**Goal:** make sure every endpoint is logged. Before this, the only logging was the notification sender, the dispatcher and
reconciler warnings, and the global error handler. **No request was logged**, so none of the endpoints were.

### What was added

- `shared/config/AccessLogFilter` writes one line per request after it finishes, for example
  `POST /api/transfers/internal -> 201 (347 ms)`. It includes the query string (kept percent-encoded, so it cannot carry a line break,
  and capped at 300 characters) and **never logs request bodies or headers**, because they will hold personal data and, from Step 13, API keys.
- `shared/config/AccessLogFilterConfig` registers it with a `FilterRegistrationBean` at `HIGHEST_PRECEDENCE + 1`, just after
  `RequestIdFilter`, so the request id is already set. As with the request-id filter, it must **not** be a `@Component`
  (problem 10 in section 8).
- **Levels:** 5xx is ERROR, 4xx is WARN, everything else INFO. A successful `/actuator` health check is DEBUG, because pollers hit it every
  few seconds; a failing one is still ERROR. A request that ends in an unhandled exception is logged as a 500 and the exception is rethrown.
- `AccessLogFilterTest`: 9 tests that attach a capturing appender to the filter's real logger and check each level, the query string, the
  long-query cut, the exception case, and that bodies and headers never appear.

> **Analogy:** a visitors' book at the front desk. Every visitor is signed in and out with the time they spent, but nobody copies down what
> was in their bag.

### A bug found while checking it

The log lines showed `[]` where the request id should be. Comparing a response's `X-Request-Id` header with the log proved the id **never reached
the logs**. Cause: `logging.pattern.correlation` was `[%X{requestId:-}] `. The `:-` default is **Logback** syntax; this project uses **Log4j2**, which
reads `requestId:-` as the key name, finds nothing and prints blank. Fixed to `[%X{requestId}] `. This closed the "unproven" note from section 7b.

### Proof against a real running app

A throwaway Postgres and the app on separate ports (not the usual ones), then 22 calls covering every endpoint: customers (create, get, change
KYC tier), accounts (open, get, by number, list), dev funding, internal transfers (new, replay, missing key), transfer list, statement, limits,
notifications, beneficiaries (resolve, save, list), an external transfer, and a 404 on an unknown path. All 22 were logged. After the fix, the
request id on each line matched the response header and the `requestId` in the error body, and a caller-supplied id (`my-demo-123`) appeared on its own line.

### Still true

The id is **not** carried onto background threads (dispatcher, reconciler, asynchronous notification listeners), so their lines show `[]`. That is
part of Step 13.

---

## 15. Status and what is left (2026-10-13)

**Built and tested:** Steps 1 to 10, with 217 passing tests. External transfers work end to end against the **simulated bank** (hold in suspense, outbox,
dispatcher with backoff, reconciler, refunds, reversal handling, alerts). Every request is access-logged with its request id.

**Not built:**

| Item | Detail |
|---|---|
| Real bank gateway | Step 11 (Paystack, or a backup from section 16). `paybridge.gateway` is `fake`. |
| Webhooks | Step 12. Needs signature checking on the raw body, de-duplication by event id, then `applyProviderResult`. The receiving side could be built and tested against the fake bank first. It would also let the "bank reverses after success" case (`7777`) be triggered by hand. |
| Authentication | Step 13. Today anyone who can reach the app can create customers and move money. |
| Rate limiting, metrics | Step 13. |
| Request id on background threads | Step 13. |
| Dev funding endpoint | `POST /api/dev/accounts/{id}/fund` creates money for anyone. Switch off with `paybridge.dev.funding-enabled=false` anywhere but a private machine. |
| `MoneyTest` gaps | See section 7b. |
| `fx`, `remittance`, `settlement`, full AML | Deferred by decision (section 2). |

**Placeholders to check before trusting:** fee bands (NGN 10 / 25 / 50), per-tier limits, and the fictional bank code `999`.

**Work that exists only in the separate `paybridge(learning copy)` folder, not in this copy:** the Dockerfile and Render deployment files, Swagger UI,
the actuator CORS fix, a React front end, and the internal, external, deployed and Swagger demo guides. That copy does not have the access log or the
request-id fix either. Bring pieces across only on request.

---

## 16. Payment providers other than Paystack (researched 2026-10-13)

The question was which other providers would suit this project given a deadline and the need to get API keys quickly. This was checked against the
providers' public developer docs on 2026-10-13. Sign-up friction and any identity checks before keys are issued could **not** be verified.

**What our code needs from a provider:** (1) an account-holder name lookup, (2) a payout that takes our own unique reference so a retry is safe,
(3) a status lookup by that reference so the reconciler can get "never heard of it", (4) webhooks, (5) a sandbox with quickly available keys.

| Provider | Sandbox keys | Fit | Concerns |
|---|---|---|---|
| **Monnify** (Moniepoint/TeamApt) | Dashboard "API Keys & Contracts"; sandbox at `sandbox.monnify.com`; single-transfer guide says disbursements are **on by default in sandbox** | Good: a validate-bank-account endpoint, transfers take our reference, account name is mandatory (matches our name-enquiry-first flow) | Older pages say disbursements start disabled; if sandbox transfers are rejected, check that setting. Live use needs activation and IP whitelisting (not needed here). |
| **Kora (Korapay)** | Dashboard test mode, then Settings, API configuration; sandbox base `api.korapay.com/sandbox/` | Reasonable: payouts, bank list, webhooks, status query | No documentation found for simulating payout success, failure or reversal in sandbox |
| **Flutterwave** | Test credentials from an onboarding sign-up; newer docs use a bearer token that expires every 10 minutes | Possible: account verification and a transfers flow | More work (token refresh); v3 and v4 docs use different hosts and auth; test-mode transfer behaviour unconfirmed |
| **Squad (GTCO)** | Not verified | Unknown | Search returned nothing; skip unless there is already an account |

**Recommendation:** stay on Paystack if a test account is quick to get; otherwise Monnify first, Kora second.

**What switching would cost** (analysis of the docs, not tested): only one class changes, because everything uses `TransferGateway`. A recipient code is a
Paystack idea; Monnify and Kora take the bank code and account number on each payout, so `createRecipient` would return a value we construct (for example
the bank and account number joined). Webhook signatures and payloads differ for Step 12, and status values need mapping to `SUCCESS`, `FAILED`, `PENDING`
and `NOT_FOUND`.
