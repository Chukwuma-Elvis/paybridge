# The FX, Remittance and Settlement Guide (explained for a 5-year-old)

**How to read this guide.** Every important piece of code is shown like this:

1. **The picture**: what it is, in everyday words (the 5-year-old version).
2. **Why we need it**: what would go wrong without it.
3. **The code**: the real code from the project.
4. **Line by line**: what each line does.

Technical words are always shown in `code font` the first time, with a plain meaning beside them, so you can say both the kid version and the grown-up version.

> **Honest note.** All of this runs against *pretend* partners: a pretend rate source, a pretend partner company, a pretend bank, and an invented "bad people" list. Every number (fees, limits, thresholds) is a made-up placeholder. Nothing here has been run against the real world.

---

# Part 0: The whole story

## 0.1 The lemonade story

Imagine your friend **Tunde lives in London** and wants to send money to his **mum in Lagos**.

* Tunde gives **dollars** to a shop in London (the **partner**).
* The shop tells **PayBridge**: "We got Tunde's dollars!"
* PayBridge swaps the dollars into **naira** (the **conversion**) and puts naira in Mum's account (the **payout**).
* If something breaks halfway, PayBridge gives Tunde's dollars back (the **refund**).
* At the end of the day, PayBridge and the London shop **compare their notebooks** to check they agree (the **settlement**).

That's everything. The rest of this guide is "how do we do each of those things without ever losing, duplicating or inventing money?"

## 0.2 The seven helpers (modules)

Think of the code as a school with seven classrooms. Each classroom has one job and doesn't poke its nose into the others' business.

| Classroom (module) | Its job, for a 5-year-old |
|---|---|
| `ledger` | The **notebook**. Every coin that moves is written here, forever, and the notebook must always add up to zero. |
| `account` | The **piggy banks** (a customer's account, or one of the bank's own boxes). |
| `fx` | The **price board and the swap desk**: today's price, a price promise (quote), the swap. |
| `compliance` | The **doorman**: checks guests against a "not allowed" list and counts how much a person may send. |
| `remittance` | The **journey manager**: walks one payment from start to finish. |
| `transfer` | The **postman**: talks to banks. We added a way for the journey manager to use it. |
| `settlement` | The **end-of-day accountant**: compares notebooks with the partner. |

## 0.3 Five big ideas used everywhere

You'll see these ideas again and again. Learn them once here.

### Idea 1: Money is a whole number of the smallest coin

We never write `10.50`. We write `1050` cents. Computers are bad at decimals (`0.1 + 0.2` isn't exactly `0.3`), but they're perfect with whole numbers. The Java type is `long`. A "minor unit" means the smallest coin: cents for dollars, kobo for naira.

### Idea 2: The notebook always adds up to zero (double-entry)

Every time money moves, we write **two or more lines** that add up to zero: "this box lost 100, that box gained 100". If the notebook ever doesn't add up, something is broken. We also check **each currency separately**: dollars add to zero, naira add to zero, and the two are never mixed in one entry.

### Idea 3: "Do it twice, get the same result" (idempotent)

Phones drop calls. Your app might say "pay!" twice because the first reply got lost. So every important action must be safe to repeat. Like pressing the lift button twice: the lift still comes once. The technical word is `idempotent`. We do it with a **unique key** (a label that can only be used once).

### Idea 4: "Only one person can grab it" (races)

Two people reach for the last biscuit at the same instant. Someone must win, and only one. In code that's a `race condition`. We solve it by letting the **database** be the referee, with one SQL statement that either succeeds (you got it) or does nothing (too late). You'll see `INSERT ... ON CONFLICT DO NOTHING` and `UPDATE ... WHERE status = 'OPEN'` many times. They are "grab the biscuit" statements.

### Idea 5: Never forget, never edit (append-only)

Important records are like writing in pen. You never rub out; to fix a mistake you write a **new** line that corrects it. Some tables even have a database guard (a `trigger`) that shouts "NO!" if anyone tries to edit or delete.

---

# Part 1: Preparing the notebook for two currencies (Step 14)

## 1.1 Naming the new kinds of entries

**The picture.** The notebook has "chapter titles" for why money moved: `TRANSFER`, `FUNDING`... We add new chapter titles for our new story.

**Why.** The notebook refuses chapter titles it doesn't know (a database rule), so we must teach it new ones.

**The code** (`EntryType.java`, the new values):

```java
REMITTANCE_FUNDING,
FX_CONVERSION_USD,
FX_CONVERSION_NGN,
REMITTANCE_PAYOUT,
SETTLEMENT_PAYMENT,
SETTLEMENT_ADJUSTMENT
```

**Line by line.**
* `REMITTANCE_FUNDING`: "the partner's dollars arrived."
* `FX_CONVERSION_USD` / `FX_CONVERSION_NGN`: the two halves of the swap (one dollar page, one naira page).
* `REMITTANCE_PAYOUT`: "naira went to the recipient."
* `SETTLEMENT_PAYMENT`: "the partner paid us what they owed."
* `SETTLEMENT_ADJUSTMENT`: "a correction because the two notebooks disagreed."

The matching migration `V11__allow_more_entry_types.sql` simply widens the database's allowed list to include these names.

## 1.2 The bank's own boxes

**The picture.** Besides customers' piggy banks, the bank owns some special boxes: a "dollars waiting" box, a "naira waiting" box, a "how many dollars do we hold" box, and so on. Each box holds **one currency only**.

**Why.** A box that held both dollars and naira would be nonsense: `100 USD + 100 NGN = ?`.

**The code** (`V12__seed_multi_currency_system_accounts.sql`):

```sql
INSERT INTO account (id, code, account_type, currency, balance_minor, status, version, created_at, updated_at)
VALUES (gen_random_uuid(), 'FX_POSITION_USD', 'SYSTEM', 'USD', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'FX_POSITION_NGN', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'REMITTANCE_CLEARING_USD', 'SYSTEM', 'USD', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'REMITTANCE_CLEARING_NGN', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now()),
       ...
```

**Line by line.**
* `INSERT INTO account (...)`: add rows to the table of piggy banks.
* `gen_random_uuid()`: make a unique id (like a fingerprint) for each box.
* `'FX_POSITION_USD'`: the box's name (`code`). "Position" means "how much of this currency do we currently hold?"
* `'SYSTEM'`: it's the bank's own box, not a customer's.
* `'USD'`: this box holds dollars only.
* `0`: starts empty. `'ACTIVE'`: open for business. `version 0`: used to detect clashing edits.
* `REMITTANCE_CLEARING_*`: "waiting rooms" where money sits between two steps of the journey.

## 1.3 Making boxes on demand

**The picture.** Each partner company needs its **own** box ("what the London shop holds for us"). We can't make these boxes in advance because we don't know the partners yet, so we make a box the first time we need it.

**Why it's tricky.** Two helpers might try to create the same box at the same instant. Without care you'd get two boxes with the same name, or a crash.

**The code** (`AccountRepository`, the "make it unless it exists" query):

```java
@Modifying
@Query(value = """
        insert into account (id, code, account_type, currency, balance_minor, status, version, created_at, updated_at)
        values (gen_random_uuid(), :code, 'SYSTEM', :currency, 0, 'ACTIVE', 0, now(), now())
        on conflict (code) do nothing
        """, nativeQuery = true)
int createSystemAccountIfAbsent(@Param("code") String code, @Param("currency") String currency);
```

**Line by line.**
* `@Modifying`: tells Spring "this query changes data."
* `insert into account ... values (...)`: try to add a new box.
* `:code`, `:currency`: blanks that get filled in with the name and currency we want.
* `on conflict (code) do nothing`: **the biscuit rule.** `code` must be unique. If a box with that name already exists, do **nothing** (no crash, no duplicate). The database applies this atomically, so two helpers at once still produce exactly one box.
* `int` result: `1` means "I made it", `0` means "it was already there."

> A lesson from testing: we first tried `try { insert } catch { ignore }`. That *doesn't work in Postgres*, because after one failed statement Postgres cancels the whole transaction. `ON CONFLICT` avoids the failure in the first place.

And the method that uses it (`AccountService.ensureSystemAccount`):

```java
accountRepository.createSystemAccountIfAbsent(code, currencyCode);
String existingCurrency = accountRepository.findCurrencyByCode(code)
        .orElseThrow(() -> new IllegalStateException("System account " + code + " vanished right after being created"));
if (!existingCurrency.equals(currencyCode)) {
    throw new PayBridgeException(ErrorCode.CONFLICT, "System account " + code + " already exists in "
            + existingCurrency + ", not " + currencyCode);
}
return accountRepository.findIdByCode(code).orElseThrow();
```

**Line by line.**
1. Make the box if it's missing.
2. Look up what currency the box has.
3. If somehow it vanished, that's a bug: shout.
4. If the box exists but in the *wrong currency* (someone asked for a dollar box named like an existing naira box), refuse with a `CONFLICT`. Never silently hand back the wrong box.
5. Return the box's id.

Notice we read only the **currency** and **id**, not the whole account. That's deliberate: see the next box.

## 1.4 The sneaky bug: remembering old photos

**The picture.** Imagine you take a photo of a whiteboard. Someone then changes the whiteboard. Later you "lock" the whiteboard and Hibernate (our database helper) is confused: "my photo says 5 but the board now says 8!"

**What happened.** Looking up an account *as a whole object* put a "photo" in Hibernate's memory. When another transaction changed the account and we later locked it, Hibernate saw a newer version than its photo and threw an error. It showed up when 8 conversions ran at the same time.

**The fix.** Ask only for what you need (the id), as plain numbers, so no photo is kept:

```java
@Query("select a.id from Account a where a.code = :code")
Optional<UUID> findIdByCode(@Param("code") String code);
```

* `select a.id`: give me just the id, not the whole account.
* `where a.code = :code`: for the box with this name.

This also quietly protected the *old* external-transfer code that looks up the `SUSPENSE` box.

## 1.5 The "does every currency add to zero?" checker

**The picture.** A teacher who, after each test, adds up all the dollars (should be 0) and all the naira (should be 0).

**The code** (`LedgerInvariants.java`, simplified):

```java
public static void everyCurrencySumsToZero(JdbcTemplate jdbc) {
    List<Map<String, Object>> totals = jdbc.queryForList(
            "select currency, sum(balance_minor) as total from account group by currency");
    for (Map<String, Object> row : totals) {
        assertThat(((Number) row.get("total")).longValue())
                .as("the %s books must sum to zero", row.get("currency")).isZero();
    }
}
```

**Line by line.**
* `select currency, sum(balance_minor) ... group by currency`: add up every account's balance, one total **per currency**.
* the loop: look at each currency's total.
* `.isZero()`: fail the test if any currency doesn't add to zero.

Almost every integration test calls this at the end. It is the safety net that proves no money was created or destroyed.

---

# Part 2: The price board, the price promise and the swap (Steps 15, 16, 18)

## 2.1 The ConversionCalculator: the only place that does money maths

**The picture.** A cashier with a strict rule: *when giving out money, always round down (give a tiny bit less); when charging a fee, always round up (charge a tiny bit more).* The shop never loses a fraction of a kobo.

**Why we need it.** Maths on money is the most dangerous code in the project. Putting it in **one** small class means there is exactly one place to get right and test.

**The code** (`ConversionCalculator.java`):

```java
public static long convert(long sellMinor, BigDecimal rate) {
    if (sellMinor < 0) throw new IllegalArgumentException("Amount cannot be negative");
    if (rate.signum() <= 0) throw new IllegalArgumentException("Rate must be positive");
    return BigDecimal.valueOf(sellMinor).multiply(rate)
            .setScale(0, RoundingMode.DOWN)
            .longValueExact();
}

public static long fee(long sellMinor, int feeBps, long minFeeMinor) {
    ...
    long percentage = BigDecimal.valueOf(sellMinor).multiply(BigDecimal.valueOf(feeBps))
            .divide(TEN_THOUSAND, 0, RoundingMode.UP)
            .longValueExact();
    return Math.max(percentage, minFeeMinor);
}
```

**Line by line: `convert`.**
* The two `if`s: negative money and zero/negative rates make no sense, so refuse them loudly.
* `BigDecimal.valueOf(sellMinor).multiply(rate)`: multiply **exactly** (no floating-point rounding errors).
* `.setScale(0, RoundingMode.DOWN)`: keep zero decimal places, and cut the leftover off (round **down**). `1485.5 x 333 = 494,671.5` becomes `494,671`.
* `.longValueExact()`: turn it back into a whole `long`, and **throw an error if it doesn't fit**, instead of quietly wrapping around to a wrong number.

**Line by line: `fee`.**
* `feeBps`: "basis points". 100 bps = 1%. 50 bps = 0.5%.
* `.multiply(feeBps).divide(10000, 0, RoundingMode.UP)`: compute `amount x bps / 10000`, rounded **up**.
* `Math.max(percentage, minFeeMinor)`: but never charge less than the minimum fee.

**A worked example.** Rate board says **1500**. We take a **1% margin** (the `spread`) so the customer rate is **1485**. Tunde sends USD 1,000.00 = `100000` cents. Fee 0.5% = `500` cents. We convert the other `99500` cents: `99500 x 1485 = 147,757,500` kobo = **NGN 1,477,575.00**.

## 2.2 Rates with a security guard (Step 15)

**The picture.** The price board shows "1 dollar = 1500 naira". But what if the supplier mistakenly types 15000? Or goes quiet and the board shows yesterday's price? A guard checks every new price before it goes up.

**The three guards** (`RatePolicy`, `FxRateStore`):
1. **Stale**: a price older than 5 minutes is not used for new quotes. *Better to close the shop than sell at yesterday's price.*
2. **Implausible**: outside a believable range (100 to 5000) → rejected as a broken feed.
3. **Spike**: more than 5% different from the last price → stored as `HELD` and an admin must approve it.

**The code** (`FxRateService.currentRate`):

```java
public CurrentRate currentRate(Corridor corridor) {
    FxRate inForce = store.latestActive(corridor).orElseThrow(() ->
            new PayBridgeException(ErrorCode.RATE_UNAVAILABLE, "No exchange rate is available for " + corridor));
    if (RatePolicy.isStale(inForce.getRetrievedAt(), clock.instant(), properties.maxRateAge())) {
        throw new PayBridgeException(ErrorCode.RATE_UNAVAILABLE,
                "The latest exchange rate for " + corridor + " is too old to use");
    }
    int spreadBps = store.spreadBps(corridor);
    return new CurrentRate(corridor, inForce.getMidRate(), RatePolicy.customerRate(inForce.getMidRate(), spreadBps),
            spreadBps, inForce.getSource(), inForce.getRetrievedAt());
}
```

**Line by line.**
* `store.latestActive(corridor)`: get the newest *accepted* price for USD→NGN.
* `.orElseThrow(...)`: if there isn't one at all, refuse (`RATE_UNAVAILABLE`, an HTTP 503).
* `RatePolicy.isStale(...)`: was it fetched longer ago than `maxRateAge`? If so, refuse.
* `spreadBps`: our margin (how many hundredths of a percent we keep).
* `RatePolicy.customerRate(mid, spread)`: the price the customer actually gets: mid price less our margin, rounded down.
* `new CurrentRate(...)`: package it all, including **where the rate came from** and **when it was true**. Every rate carries its receipt.

**Why prices are "append-only".** The rate table has a trigger that forbids `UPDATE` and `DELETE`:

```sql
CREATE TRIGGER trg_fx_rate_append_only
    BEFORE UPDATE OR DELETE ON fx_rate
    FOR EACH ROW EXECUTE FUNCTION fx_forbid_change();
```

* `BEFORE UPDATE OR DELETE`: just before anyone tries to change or remove a row...
* `EXECUTE FUNCTION fx_forbid_change()`: ...run a function that raises an error. Price history is a diary: you add new entries, you never erase old ones. Approving a held price is a **new row** that points at the held one.

## 2.3 The quote: a price promise that works once (Step 16)

**The picture.** "I promise: for the next 5 minutes, USD 1,000 gets you NGN 1,477,575." A concert ticket: it works **once**, and it **expires**.

**Why.** Rates move constantly. Without a promise the customer can't know what they'll get. Without "works once", one quote could pay for two remittances. Without expiry, a promise from last week could be used today.

### Making a quote

**The code** (`FxQuoteRepository.issueIfAbsent`, shortened):

```java
insert into fx_quote (id, reference, corridor, sell_minor, fee_minor, buy_minor, mid_rate, customer_rate, ...
                      expires_at, status, requested_by, idempotency_key, request_hash, created_at)
values (:id, :reference, :corridor, :sell, :fee, :buy, :mid, :customerRate, ...
        now() + (:ttlSeconds * interval '1 second'), 'OPEN', :requestedBy, :idempotencyKey, :requestHash, now())
on conflict (requested_by, idempotency_key) do nothing
```

**Line by line.**
* `sell_minor, fee_minor, buy_minor`: the three numbers promised: what the sender pays, our fee, what the recipient gets. They're **stored**, frozen forever, so later rate changes can't alter the promise.
* `expires_at = now() + ttl`: expiry is worked out by the **database's clock**. (Using one clock, the database's, for both writing and checking avoids "my computer's clock is 3 seconds off" bugs.)
* `'OPEN'`: the ticket is unused.
* `requested_by, idempotency_key`: *who asked* and *their unique label for this request*.
* `on conflict (requested_by, idempotency_key) do nothing`: if the same person sends the same label again (a retry), don't make a second quote; the service will hand back the first.
* `request_hash`: a fingerprint of the request. If someone reuses a label for a **different** request (say a different amount) we notice and refuse (`IDEMPOTENCY_KEY_REUSED`).

And the service logic that surrounds it (`QuoteService.create`):

```java
var existing = quotes.findByRequestedByAndIdempotencyKey(requestedBy, idempotencyKey);
if (existing.isPresent()) {
    return replay(existing.get(), hash);
}

CurrentRate rate = rates.currentRate(corridor);
long fee = ConversionCalculator.fee(sellMinor, limits.feeBps(), limits.minFeeMinor());
...
long buy = ConversionCalculator.convert(sellMinor - fee, rate.customerRate());
...
int inserted = quotes.issueIfAbsent(...);
if (inserted == 0) {
    return replay(quotes.findByRequestedByAndIdempotencyKey(requestedBy, idempotencyKey).orElseThrow(), hash);
}
return new QuoteIssued(view(quotes.findById(id).orElseThrow()), true);
```

* First, **have I seen this label before?** If yes, `replay` returns the original quote (or refuses if the request was different).
* `rates.currentRate(...)`: fetch a fresh, non-stale price (or fail).
* `fee` then `buy`: compute the numbers with the calculator. Note `sellMinor - fee`: the fee is taken **first**, then the rest is converted.
* `issueIfAbsent`: try to save. `0` means "someone with the same label beat me by a millisecond": give back **their** quote.
* the last line: return the new quote, with `created = true` so the web layer can answer `201 Created` (and `200 OK` on a replay).

### Using a quote (the most important statement in Part 2)

**The code** (`FxQuoteRepository.consume`):

```java
update fx_quote
   set status = 'CONSUMED', consumed_by = :consumer, consumed_at = now()
 where id = :id and status = 'OPEN' and expires_at > now()
```

**Line by line.**
* `set status = 'CONSUMED', consumed_by = :consumer`: tear the ticket and write who used it.
* `where id = :id`: this particular ticket...
* `and status = 'OPEN'`: ...only if it is **still unused**...
* `and expires_at > now()`: ...and **not expired** (by the database clock).

This is **one statement**. The database locks the row, so if ten people tear the same ticket at once, the first one changes it and every other one finds `status` is no longer `'OPEN'`, so they change **0 rows**. The test `whenManyTryToUseOneQuoteAtOnceExactlyOneWins` runs ten threads and checks exactly one gets `1`.

Afterwards the service looks at *why* a try failed:

```java
if (quotes.consume(quoteId, consumerId) == 1) {
    return view(quotes.findById(quoteId).orElseThrow());
}
FxQuote now = quotes.findById(quoteId).orElseThrow();
if (now.getStatus() == QuoteStatus.CONSUMED) {
    if (consumerId.equals(now.getConsumedBy())) {
        return view(now);          // a retry by the SAME user: fine
    }
    throw new PayBridgeException(ErrorCode.INVALID_STATE, "... has already been used");
}
...
throw new PayBridgeException(ErrorCode.QUOTE_EXPIRED, "... has expired; request a new one");
```

* `== 1`: I tore it, here it is.
* Already consumed **by me** (a retry): return it, harmless.
* Already consumed **by someone else**: refuse.
* Otherwise it must have expired: say so.

## 2.4 The swap: a conversion is two notebook entries (Step 18)

**The picture.** Two cash drawers at the swap desk. The **dollar drawer** receives dollars; the **naira drawer** gives out naira. Each drawer balances by itself, so we write **two** entries, one per drawer, and tie them together.

**Why not one entry?** One entry may hold only one currency and must add to zero. A dollar-and-naira entry could never add to zero.

```
Dollar entry:  clearing USD  -100,000     FX position USD  +99,500     fees USD  +500
Naira entry:   FX position NGN -147,757,500     clearing NGN  +147,757,500
```

* "clearing" boxes are the waiting rooms. Dollars leave the dollar waiting room; naira enter the naira waiting room.
* "FX position" boxes record what the **house** now holds: we are *long* (own) dollars and *short* (owe) naira.
* The fee goes to a `FEES_USD` box.

**The code** (`ConversionService.convert`, the heart):

```java
FxQuote quote = quotes.findById(quoteId)
        .orElseThrow(() -> new ResourceNotFoundException("Quote", quoteId));
if (quote.getStatus() != QuoteStatus.CONSUMED) {
    throw new PayBridgeException(ErrorCode.INVALID_STATE,
            "Quote " + quote.getReference() + " must be used by a remittance before it can be converted");
}
Optional<Conversion> existing = find(quoteId);
if (existing.isPresent()) {
    return existing.get();
}
```

* Find the quote. It must have been **used** by a remittance (no converting "free" quotes).
* If we already converted it, **return that** (idempotent: converting twice posts nothing twice).

```java
UUID positionUsd = accounts.systemAccountId(SystemAccount.FX_POSITION_USD);
UUID positionNgn = accounts.systemAccountId(SystemAccount.FX_POSITION_NGN);
UUID feesUsd = fee > 0 ? accounts.ensureSystemAccount(FEES_USD, "USD") : null;
List<UUID> involved = new ArrayList<>(List.of(usdSourceAccountId, ngnTargetAccountId, positionUsd, positionNgn));
if (feesUsd != null) involved.add(feesUsd);
List<AccountSummary> locked = accounts.lockAccounts(involved);
requireCurrency(locked, usdSourceAccountId, USD, "The dollar clearing account");
requireCurrency(locked, ngnTargetAccountId, NGN, "The naira clearing account");
```

* Work out the ids of every box we'll touch.
* `lockAccounts(involved)`: **lock them all at once, in one fixed order.** *Like four kids who all need the same crayons: if everyone picks them up in the same order nobody ends up holding one crayon waiting for another forever (a `deadlock`).*
* `requireCurrency`: with everything locked, check the two clearing boxes really are USD and NGN. A wrong box → `CURRENCY_MISMATCH`.

```java
PostedEntry usdEntry = ledger.post(new PostingRequest(EntryType.FX_CONVERSION_USD, reference, ..., usdLines));
PostedEntry ngnEntry = ledger.post(PostingRequest.of(EntryType.FX_CONVERSION_NGN, reference, ...,
        new PostingLine(positionNgn, Money.ofMinor(-buy, NGN)),
        new PostingLine(ngnTargetAccountId, Money.ofMinor(buy, NGN))));

jdbc.update("""
        insert into fx_conversion (...) values (...)
        on conflict (quote_id) do nothing
        """, ...);
```

* Post the dollar entry, then the naira entry. Both are inside one database transaction, so it is **both or neither**: the test `whenTheSecondLegFailsTheFirstIsRolledBack` freezes the naira box and checks the dollar leg vanished too.
* The `reference` (`"CONV-" + quote.getReference()`) is the idempotency label: the ledger itself refuses to post the same (type, reference) twice.
* `fx_conversion` records the link between the two entries. `quote_id` is UNIQUE, so a quote can only ever be converted once, enforced by the database.

**Undoing it.** `reverse(...)` calls `ledger.reverse` on **both** entries (each becomes a new "opposite" entry, nothing is erased) and marks the conversion `REVERSED`. Reversing twice does nothing the second time. The test `reversingReturnsBothPositionsExactly` checks every box is back to exactly where it was.

---

# Part 3: The doorman (compliance, Step 17)

## 3.1 What the doorman does

**The picture.** A doorman at a club with a clipboard. He (1) compares each guest's name with a "not allowed" list, (2) counts how many times a guest has come in, and (3) makes sure nobody takes more than their allowance. If he is **unsure**, he does not guess: he asks the manager and the guest waits outside.

**Why.** Banks are legally required to stop dirty money (AML = anti-money-laundering). A payment system that never checks would be shut down.

## 3.2 Comparing names: NameSimilarity

**The picture.** "Okafor Chidi" and "chidi okafor" are the same person. So we tidy both names first, then count how many letter-changes turn one into the other.

**The code** (`NameSimilarity.java`):

```java
static String clean(String name) {
    String plain = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9 ]", " ")
            .trim();
    String[] words = plain.split("\\s+");
    Arrays.sort(words);
    return String.join(" ", words);
}

static double between(String a, String b) {
    ...
    int longest = Math.max(left.length(), right.length());
    return 1.0 - (double) editDistance(left, right) / longest;
}
```

**Line by line.**
* `Normalizer.normalize(... NFD)` + `replaceAll("\\p{M}", "")`: split "é" into "e" + an accent mark, then delete accent marks. "José" becomes "Jose".
* `.toLowerCase`: ignore capitals.
* `replaceAll("[^a-z0-9 ]", " ")`: turn punctuation into spaces.
* `Arrays.sort(words)`: **sort the words**, so word order doesn't matter ("Okafor Chidi" = "Chidi Okafor").
* `editDistance`: the smallest number of single-letter changes (add, remove, swap) to turn one name into the other.
* `1.0 - distance / longest`: turn that into a score: `1.0` = identical, `0.0` = nothing alike.

## 3.3 Deciding: ScreeningPolicy

**The picture.** A traffic light: **green** (CLEAR), **yellow** (REVIEW: a person must look), **red** (BLOCK: stop). We also look for *backing evidence*: does the country match? the birth date?

**The code** (`ScreeningPolicy.outcomeFor`):

```java
static ScreeningOutcome outcomeFor(double similarity, int corroborators, AmlProperties.Screening cfg) {
    if (similarity >= cfg.blockThreshold() && corroborators >= 1) {
        return ScreeningOutcome.BLOCK;
    }
    if (similarity >= cfg.reviewThreshold()) {
        return ScreeningOutcome.REVIEW;
    }
    if (similarity >= cfg.corroboratedReview() && corroborators >= 2) {
        return ScreeningOutcome.REVIEW;
    }
    return ScreeningOutcome.CLEAR;
}
```

**Line by line** (using the placeholder numbers 0.95 / 0.85 / 0.70).
1. Names almost identical (95%+) **and** something else matches → **BLOCK**.
2. Names 85%+ alike, even with nothing else matching → **REVIEW**. This is the key rule: *a doubtful match is never waved through.*
3. Names only 70%+ alike, but **both** country and birth date match → **REVIEW**.
4. Anything else → **CLEAR**. A weak name match with no support is probably just two different people with similar names.

The thresholds are **configuration** (`application.yml`), not hard-coded, because a compliance officer, not a programmer, should decide them.

## 3.4 The case file: ScreeningService

**The picture.** Every yellow/red result opens a **case file** in a cabinet. Files are never shredded. A manager reads it, then writes a decision **and a reason** on it. Once decided, the file is sealed.

**The database guard** (`V15__create_aml.sql`):

```sql
CREATE FUNCTION screening_case_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'screening cases are never deleted' USING ERRCODE = '23000';
    END IF;
    IF OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'a decided screening case never changes' USING ERRCODE = '23000';
    END IF;
    ...
```

* `TG_OP = 'DELETE'`: someone tried to delete → refuse.
* `OLD.status <> 'OPEN'`: someone tried to change a case that is already decided → refuse.
* Even a bug or a rogue developer can't tamper with the evidence, because the guard lives inside the database.

Two more rules in the table definition:

```sql
CONSTRAINT ck_screening_case_decided CHECK (
    (status = 'OPEN' AND decided_by IS NULL AND ...)
    OR
    (status <> 'OPEN' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND length(trim(decision_reason)) > 0)
),
CONSTRAINT ck_screening_case_block CHECK (NOT (outcome = 'BLOCK' AND status = 'APPROVED'))
```

* A decided case **must** say who, when, and a non-blank reason. An undecided case must have none of those.
* A `BLOCK` case can **never** be approved here (it must be escalated outside the system); it can only be rejected.

**Deciding a case** (`ScreeningService.decideCase`):

```java
int updated = jdbc.update("""
        update screening_case set status = ?, decision_reason = ?, decided_by = ?, decided_at = now()
        where id = ? and status = 'OPEN'
        """, decision.resultingStatus().name(), reason.trim(), decidedBy, caseId);
CaseView after = getCase(caseId);
if (updated == 0) {
    return sameDecisionOrRefuse(after, decision);
}
```

* `where ... status = 'OPEN'`: the biscuit rule again. Two managers deciding at once: one wins (`updated = 1`), the other gets `0`.
* `sameDecisionOrRefuse`: if the loser asked for the **same** decision, just show them the result (no harm). If they asked for the **opposite**, refuse with `INVALID_STATE`: *you can't un-approve what someone already approved.*

## 3.5 The allowance meter: USD limits

**The picture.** A jar of tokens per person per day and per month. Taking tokens must be a single swift motion, so two people can't both grab the "last" token.

**The code** (`UsdLimitService.add`):

```java
insert into usd_usage (id, scope, subject_key, period_kind, period_start, total_minor)
values (gen_random_uuid(), ?, ?, ?, ?, ?)
on conflict (scope, subject_key, period_kind, period_start)
do update set total_minor = usd_usage.total_minor + excluded.total_minor
where usd_usage.total_minor + excluded.total_minor <= ?
```

**Line by line.**
* The row is "how much has *this person* (`subject_key`) used in *this period* (`period_kind` D = day, M = month)".
* `insert ... values`: if there's no row yet, create one with this amount.
* `on conflict (...) do update set total = total + new`: if a row already exists, **add** the new amount to it.
* `where total + new <= limit`: **but only if the result stays within the limit.** If not, the statement changes 0 rows.
* The method returns `== 1` (allowed) or not (refused). The test with 12 simultaneous payments of USD 1,000 against a USD 10,000 allowance lets **exactly 10** through.

And the caller:

```java
if (!add(scope, subjectKey, 'D', day, usdMinor, limit.dailyMinor())) {
    throw exceeded("This would take the " + who + " over today's limit ...");
}
if (!add(scope, subjectKey, 'M', day.withDayOfMonth(1), usdMinor, limit.monthlyMinor())) {
    subtract(scope, subjectKey, 'D', day, usdMinor);   // put back what was just counted
    throw exceeded("... over this month's limit ...");
}
```

* Count against **today** first; if over, refuse.
* Then count against **this month**; if that fails, **give the daily tokens back** (`subtract`) before refusing, so a refused payment never leaves a "ghost" use behind.
* `releaseUsd` later gives tokens back when a remittance fails or is refunded.

## 3.6 The pattern spotter: VelocityService

**The picture.** The doorman notices "this guest has come in five times in an hour" or "three guests each carrying exactly just-under-the-limit amounts". Suspicious patterns, even if each visit alone looks fine.

**The code** (`VelocityService.assess`, shortened):

```java
jdbc.update("""
        insert into velocity_event (id, subject_key, event_ref, amount_minor, created_at)
        values (gen_random_uuid(), ?, ?, ?, ?)
        on conflict (subject_key, event_ref) do nothing
        """, subjectKey, eventRef, amountMinor, Timestamp.from(now));

Integer recent = jdbc.queryForObject(
        "select count(*) from velocity_event where subject_key = ? and created_at > ?", ...);
if (recent != null && recent >= rules.maxCount()) {
    return Optional.of("VELOCITY: " + recent + " payments within " + rules.window().toHours() + " hours");
}

long floor = rules.thresholdMinor() * rules.bandPercent() / 100;
Integer justUnder = jdbc.queryForObject("""
        select count(*) from velocity_event
        where subject_key = ? and created_at > ? and amount_minor >= ? and amount_minor < ?
        """, ...);
if (justUnder != null && justUnder >= rules.structuringCount()) {
    return Optional.of("STRUCTURING: ...");
}
return Optional.empty();
```

**Line by line.**
* First, **write down this visit**. `on conflict (subject_key, event_ref) do nothing` makes writing it down idempotent: asking twice about the same payment doesn't count it twice.
* Count visits in the last 24 hours. Five or more → flag `VELOCITY`.
* `floor` = 90% of the reporting threshold. Count visits whose amount is between 90% and 100% of the threshold. Three or more → flag `STRUCTURING` (the trick of splitting a big amount into several just-under-the-line pieces).
* The method **only raises a flag**; it never blocks. A person decides.

## 3.7 The reception desk: ComplianceService

`ComplianceService` implements `ComplianceApi` and simply hands each question to the right helper: `screen` → `ScreeningService`, `reserveUsd` → `UsdLimitService`, `assessVelocity` → `VelocityService`. *A receptionist who doesn't do the work, just sends you to the right desk.* That keeps each helper small and testable, and other modules only ever talk to the one `ComplianceApi` door.

---

# Part 4: Starting a remittance (Steps 19 and 20)

## 4.1 The journey map: the state machine

**The picture.** A board game. A piece sits on one square and can only move to certain next squares. You can't jump from the start to "PAID".

```
CREATED -> SCREENING -> AWAITING_FUNDING -> FUNDED -> CONVERTED -> PAYOUT_PENDING -> PAID
              |  ^           |                              \________________________/
              v  |           v                                         |
        UNDER_REVIEW     FAILED / CANCELLED                      REFUND_PENDING -> REFUNDED
```

**Why.** The most dangerous bugs in payment code are "impossible" states: a remittance that is PAID but never funded. If the rules are written once and enforced everywhere, those states simply can't exist.

**The code** (`RemittanceStatus.canMoveTo`):

```java
public boolean canMoveTo(RemittanceStatus next) {
    return switch (this) {
        case CREATED -> next == SCREENING || next == FAILED;
        case SCREENING -> next == UNDER_REVIEW || next == AWAITING_FUNDING || next == FAILED;
        case UNDER_REVIEW -> next == AWAITING_FUNDING || next == FAILED || next == CANCELLED;
        case AWAITING_FUNDING -> next == FUNDED || next == UNDER_REVIEW || next == FAILED || next == CANCELLED;
        case FUNDED -> next == CONVERTED || next == REFUND_PENDING;
        case CONVERTED -> next == PAYOUT_PENDING || next == PAID || next == REFUND_PENDING;
        case PAYOUT_PENDING -> next == PAID || next == REFUND_PENDING;
        case REFUND_PENDING -> next == REFUNDED;
        case PAID, FAILED, REFUNDED, CANCELLED -> false;
    };
}
```

**Line by line.** Each `case` is "if I'm on this square..." and the right-hand side lists the squares I may step to. The last line says the four **final** squares have no exits. A test checks all 144 possible (from, to) pairs against a written-out table.

**Enforcing it** (`Remittance.moveTo`):

```java
public void moveTo(RemittanceStatus next, String reason) {
    if (!status.canMoveTo(next)) {
        throw new PayBridgeException(ErrorCode.INVALID_STATE,
                "Remittance " + reference + " cannot move from " + status + " to " + next);
    }
    this.status = next;
    this.statusReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 255));
}
```

* If the move is illegal → throw an error; the status doesn't change.
* Otherwise set the new status and remember **why** (shortened to 255 chars so it fits the column).
* The `status` field has **no setter**: `moveTo` is the only door in. No code anywhere can sneak a remittance into a bad state.

## 4.2 The diary: RemittanceAudit

**The picture.** Every time the piece moves, we write one line in a diary: "from, to, why, when".

**The code** (`RemittanceAudit.move`):

```java
void move(Remittance remittance, RemittanceStatus next, String note) {
    RemittanceStatus from = remittance.getStatus();
    remittance.moveTo(next, note);
    record(remittance.getId(), from, next, note);
}
```

* Remember where we were, move (which may throw), then write the diary line. Because `move` is the only way code changes status, **the status and the diary can't disagree.** The diary table has a "no editing, no deleting" trigger.
* `record` also bumps a counter `paybridge.remittance.transitions` **only after the transaction commits**, using `TransactionSynchronization.afterCommit`. *We only brag about a move once it is really saved.* If a creation is rolled back, the counter doesn't move.

## 4.3 Creating a remittance: RemittanceService.create

This is the front door. We take it in five stops.

### Stop 1: check the request is sane

```java
validate(command);
String partner = command.partnerCode().toLowerCase(Locale.ROOT);
String hash = hash(command, partner);
UUID id = UUID.randomUUID();
```

* `validate`: known partner, a 10-digit account number, a bank code if the recipient is at a bank, two-letter countries... Bad input is refused **before** anything is touched.
* `hash`: a fingerprint of the request (used for the repeat check next).
* `id`: the new remittance's id, chosen now so we can give it to the quote in Stop 3.

### Stop 2: the "have I seen this?" ticket

```java
if (repository.claim(clientName, idempotencyKey, hash, id) == 0) {
    String earlierHash = repository.claimedHash(clientName, idempotencyKey).orElseThrow();
    if (!earlierHash.equals(hash)) {
        throw new PayBridgeException(ErrorCode.IDEMPOTENCY_KEY_REUSED, ...);
    }
    UUID earlier = repository.claimedRemittance(clientName, idempotencyKey).orElseThrow();
    return new Created(RemittanceMapper.view(find(earlier)), false);
}
```

* `claim` is `INSERT ... ON CONFLICT DO NOTHING` into the `remittance_claim` table. **1** = "you're the first with this label". **0** = "someone already used this label".
* On `0`: if the earlier request had a **different fingerprint**, refuse. If it was the **same**, find the remittance made earlier and return it with `created = false` (the web layer answers `200 OK` instead of `201 Created`).
* If two identical requests arrive together, the database makes the second one *wait* until the first commits, then tells it `0`. No double remittance.

### Stop 3: use up the quote, and check the partner reference

```java
if (repository.existsByPartnerCodeAndPartnerReference(partner, command.partnerReference())) {
    throw new PayBridgeException(ErrorCode.CONFLICT, "The partner reference " + ... + " was already used");
}
Quote quote = fx.consumeQuote(command.quoteId(), id);
requireInSystemRecipientIsUsable(command.recipient());
```

* The partner's own reference for this payment may be used **once** (stops the partner accidentally sending the same payment twice under different labels).
* `fx.consumeQuote(quoteId, id)`: tear the ticket (Part 2.3). It throws `RESOURCE_NOT_FOUND`, `QUOTE_EXPIRED` or `INVALID_STATE` if the ticket is missing, old or used. Notice we pass **this remittance's id** as the consumer: that's how a quote is tied to exactly one remittance.
* If the recipient is a PayBridge customer, make sure their account really exists, is a naira customer account, and is active. *We'd hate to take Tunde's dollars and then discover Mum's account doesn't exist.*

### Stop 4: write it down

```java
Remittance remittance = repository.saveAndFlush(Remittance.create(new Remittance.Facts(id, reference, clientName,
        idempotencyKey, hash, partner, command.partnerReference(), quote.corridor(), quote.id(), quote.sellMinor(),
        quote.feeMinor(), quote.buyMinor(), senderKey, ..., clock.instant().plus(properties.fundingWindow()))));
audit.recordCreated(remittance);
```

* The amounts (`sellMinor`, `feeMinor`, `buyMinor`) are copied **from the quote**. The caller can't claim "I want 1,000 dollars" with a quote for 50: there's no amount field in the request at all.
* `senderKey` = `partner:senderReference`. This is the "who is this person?" label for limits and velocity.
* `fundingWindow` (24 hours): how long the partner has to confirm the dollars before we give up.
* `audit.recordCreated`: first diary line.

### Stop 5: the doorman

```java
gate.run(remittance);
return new Created(RemittanceMapper.view(remittance), true);
```

The next section explains `gate.run`. Everything from Stop 2 to here is **one database transaction**: if any step throws, *everything* rolls back, including the claim and the quote tear. The test `aRefusedCreationLeavesNothingBehindAndTheQuoteStaysUsable` proves a refused request can be retried with the same quote.

### The view hides secrets

```java
static String mask(String accountNumber) {
    if (accountNumber == null || accountNumber.length() <= 4) return "****";
    return "******" + accountNumber.substring(accountNumber.length() - 4);
}
```

Clients only ever see `******1234`. A test checks the JSON response never contains the full number.

## 4.4 The gate: ScreeningGate.run

**The picture.** The doorman, wired into the journey.

```java
audit.move(r, RemittanceStatus.SCREENING, ...);
if (!properties.screeningGateEnabled()) {          // development only!
    audit.move(r, RemittanceStatus.AWAITING_FUNDING, null);
    return;
}
LocalDate day = compliance.reserveUsd(UsdLimitScope.SENDER, r.getSenderKey(), r.getSellMinor());
compliance.reserveUsd(UsdLimitScope.RECIPIENT, r.getRecipientKey(), r.getSellMinor());
r.limitsReserved(day);

ScreeningResult sender = compliance.screen(new Party(r.getSenderName(), r.getSenderCountry(), r.getSenderBirth()), "SENDER", r.getReference());
ScreeningResult recipient = compliance.screen(new Party(r.getRecipientName(), r.getRecipientCountry(), null), "RECIPIENT", r.getReference());
Optional<String> pattern = compliance.assessVelocity(r.getSenderKey(), r.getReference(), r.getSellMinor());
UUID velocityCase = pattern.map(reason ->
        compliance.openReviewCase(r.getReference(), "VELOCITY", r.getSenderName(), reason).id()).orElse(null);
r.recordCases(sender.caseId(), recipient.caseId(), velocityCase);

if (sender.caseId() == null && recipient.caseId() == null && velocityCase == null) {
    audit.move(r, RemittanceStatus.AWAITING_FUNDING, null);
    return;
}
... audit.move(r, RemittanceStatus.UNDER_REVIEW, reason);
events.publishEvent(new RemittanceUnderReview(...));
```

**Line by line.**
1. Step onto the `SCREENING` square.
2. The `screeningGateEnabled` flag is a **development-only** switch to skip checks. It defaults to **on**.
3. **Reserve the USD allowance first** for both the sender and the recipient. We do this *before* screening so that a refusal for "over the limit" leaves no case files behind.
4. Screen the sender and the recipient, and look for suspicious patterns. A suspicious pattern becomes its own review case (`openReviewCase`).
5. `recordCases`: remember the case ids on the remittance.
6. **All clear** (no case ids) → `AWAITING_FUNDING`: waiting for the partner's dollars.
7. **Anything doubtful** → `UNDER_REVIEW`, plus an event so operators hear about it. **Nothing moves until a person decides.** The test `nothingMovesWhileAHitIsUnresolved` sends a funding confirmation for an `UNDER_REVIEW` remittance and checks no money entry appears.

## 4.5 Deciding a review

`ReviewService.decide` gives every case of the remittance the same decision and reason:

```java
for (UUID caseId : new UUID[]{r.getSenderCaseId(), r.getRecipientCaseId(), r.getVelocityCaseId(), r.getFundingCaseId()}) {
    if (caseId != null) {
        compliance.decideCase(caseId, decision, reason, decidedBy);
    }
}
gate.resolve(r);
```

* Loop over up to four cases; skip empty ones.
* Open cases get decided; a case already decided the *same* way is left alone; one decided the *opposite* way throws (history can't be rewritten).
* `gate.resolve(r)`: look at the cases again.

```java
if (cases.stream().anyMatch(c -> c.status() == CaseStatus.OPEN)) return false;
Optional<CaseView> rejected = cases.stream().filter(c -> c.status() == CaseStatus.REJECTED).findFirst();
if (rejected.isPresent()) {
    releaseLimits(r);
    audit.move(r, RemittanceStatus.FAILED, "Review rejected: " + rejected.get().decisionReason());
    return true;
}
audit.move(r, RemittanceStatus.AWAITING_FUNDING, "Review approved");
return true;
```

* Any case still open? Do nothing yet.
* Any case **rejected** → give the allowance back, mark `FAILED`.
* Otherwise everyone approved → back to `AWAITING_FUNDING`.
* If a compliance officer decided on the compliance desk instead, the background engine runs `resolve` and notices (test `aDecisionDoneOnTheComplianceDeskIsPickedUpByTheEngine`).

---

# Part 5: The partner says "we got the dollars" (Step 21)

## 5.1 Why a webhook, and why we don't just believe it

**The picture.** The London shop phones PayBridge: "Tunde's dollars arrived!" Anyone could phone and *lie*. So the shop stamps every message with a **secret wax seal** that only the shop and PayBridge know how to make. PayBridge remakes the seal and compares. A `webhook` is simply "a message another computer sends to us". The seal is an `HMAC signature`.

Our rule is the same as for bank webhooks: **seal first, then everything else**:
1. Is the seal genuine? (else 401)
2. Can we read it? (else 400)
3. Have we seen this exact message before? (else, ignore the repeat)
4. Do we expect it, and do the amount and currency match? (else, don't apply it)
5. Apply it.

## 5.2 The wax seal: PartnerWebhookProvider

**The code** (shortened):

```java
@Override
public boolean hasValidSignature(byte[] rawBody, Function<String, String> header) {
    String partner = peekPartner(rawBody);
    String secret = partner == null ? null : secrets.get(partner);
    if (secret == null) {
        return false;
    }
    return Hmac.signatureMatches(Hmac.hmacSha512Hex(secret, rawBody), header.apply(SIGNATURE_HEADER));
}
```

**Line by line.**
* The message says which partner it is from (`"partner":"fakepartner"`). `peekPartner` reads **only that name**.
* `secrets.get(partner)`: look up *that partner's own* secret. Unknown partner → no secret → reject.
* `Hmac.hmacSha512Hex(secret, rawBody)`: make the seal ourselves from the **raw bytes** exactly as they arrived (re-writing the JSON could change a byte and ruin the seal).
* `Hmac.signatureMatches(...)`: compare in **constant time** so an attacker can't learn the seal letter by letter from how long the comparison takes.

**Why peeking at the name before checking the seal is safe.** The name is only used to *choose which secret to try*. If someone claims "I'm Partner B" but signed with Partner A's secret, the seal fails. The test `onePartnerCannotSpeakForAnotherEvenWithAValidSecretOfItsOwn` proves this.

## 5.3 One pipeline, two kinds of message

The old pipeline only understood "a payment provider says our transfer succeeded". We taught it a second **kind**:

```java
enum Kind { TRANSFER_OUTCOME, PARTNER_FUNDING }
default Kind kind() { return Kind.TRANSFER_OUTCOME; }
```

* Every existing provider (Paystack, Monnify) keeps `TRANSFER_OUTCOME` by default, so their behaviour and all their old tests are unchanged.
* The partner provider overrides `kind()` to `PARTNER_FUNDING`.

Inside `WebhookService.handle`, after the seal and de-duplication checks:

```java
if (provider.kind() == WebhookProvider.Kind.PARTNER_FUNDING) {
    return handleFunding(name, event, claim);
}
if (event.reference() != null && event.reference().startsWith(SystemPayoutService.PREFIX)) {
    return handlePayout(name, event, claim);
}
```

* Funding messages go to `handleFunding`. Messages about references starting `PO-` (our payouts) go to `handlePayout`. Everything else follows the old path.

**Why a handler interface?** The `transfer` module must not know what a "remittance" is. So it defines a tiny interface and the remittance module plugs in:

```java
public interface PartnerFundingHandler {
    Optional<ExpectedFunding> expectedFunding(String partner, String reference);
    FundingResult applyFunding(String partner, String reference, boolean funded, String partnerReference, String message);
    void flagMismatch(String partner, String reference, String detail);
}
```

* `expectedFunding`: "do you have something waiting for this? how much should it be?"
* `applyFunding`: "do it".
* `flagMismatch`: "the amounts disagreed: raise it with a person".

*Like a hotel reception that doesn't know what each guest does, only that there's a person in room 12 to hand the message to.*

## 5.4 handleFunding, line by line

```java
String partner = event.partner();
PartnerFundingHandler handler = fundingHandlers.isEmpty() ? null : fundingHandlers.get(0);
Optional<PartnerFundingHandler.ExpectedFunding> expected = handler == null || partner == null || event.reference() == null
        ? Optional.empty() : handler.expectedFunding(partner, event.reference());
if (expected.isEmpty()) {
    recorder.finish(claim.eventRowId(), WebhookStatus.UNMATCHED, "Nothing is waiting for funding with this reference");
    return done(name, event, Result.UNMATCHED);
}
```
* Ask "are we expecting this?" If not (wrong reference, or the *other* partner's remittance), record `UNMATCHED` and stop. The test `aPartnerCannotFundAnotherPartnersRemittance` checks this.

```java
boolean funded = event.outcome() == GatewayOutcome.SUCCESS;
if (funded) {
    String disagreement = disagreement(event, expected.get().amountMinor(), expected.get().currency());
    if (disagreement != null) {
        handler.flagMismatch(partner, event.reference(), disagreement);
        recorder.finish(claim.eventRowId(), WebhookStatus.AMOUNT_MISMATCH, disagreement);
        return done(name, event, Result.AMOUNT_MISMATCH);
    }
}
```
* If the partner says "funded", check the **amount and currency** against our record. *Never trust a number in a message just because it's signed.* Different → raise a flag, record `AMOUNT_MISMATCH`, apply nothing.

```java
PartnerFundingHandler.FundingResult result = handler.applyFunding(...);
if (result == PartnerFundingHandler.FundingResult.HELD) {
    recorder.finish(claim.eventRowId(), WebhookStatus.MANUAL_REVIEW, "Held: not ready ...");
    return done(name, event, Result.MANUAL_REVIEW);
}
...
recorder.finish(claim.eventRowId(), WebhookStatus.APPLIED, null);
```
* `HELD` means "not ready yet" (still under review). We record it and tell the partner "OK, received" (so it stops resending). The safety-net check picks the funding up later.
* Errors with `INVALID_STATE` become `MANUAL_REVIEW` (a human must look). Other errors are recorded as `FAILED` and re-thrown so the partner's retry tries again.
* The record of "what happened to this message" is written in **its own short transaction** so it survives even if applying fails.

## 5.5 FundingService.applyFunding, line by line

```java
Remittance r = repository.findByReferenceForUpdate(reference)
        .filter(found -> found.getPartnerCode().equalsIgnoreCase(partner))
        .orElseThrow(() -> new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, ...));
RemittanceStatus status = r.getStatus();
switch (status) {
    case AWAITING_FUNDING -> {
        if (funded) { postFunding(r); } else { gate.releaseLimits(r); audit.move(r, FAILED, ...); }
        return FundingResult.APPLIED;
    }
    case CREATED, SCREENING, UNDER_REVIEW -> { return FundingResult.HELD; }
    case FUNDED, CONVERTED, PAYOUT_PENDING, PAID, REFUND_PENDING, REFUNDED -> {
        if (!funded) throw new PayBridgeException(ErrorCode.INVALID_STATE, ...);
        return FundingResult.APPLIED;        // the partner repeated itself
    }
    case FAILED, CANCELLED -> {
        if (funded) throw new PayBridgeException(ErrorCode.INVALID_STATE, ...MANUAL REVIEW NEEDED);
        return FundingResult.APPLIED;
    }
}
```

* `findByReferenceForUpdate`: **lock the row** (`SELECT ... FOR UPDATE`). While we work, nobody else can change this remittance. *Like putting a "do not disturb" sign on the door.*
* `.filter(partnerCode matches)`: you can only fund *your own* remittances.
* The `switch` is a table of "what if we are on this square":
  * **Waiting for funding** → the normal case: post the funding (or, if the partner says it failed, give back the allowance and fail).
  * **Still being checked** → don't touch anything: `HELD`.
  * **Already funded or further** → the partner just repeated itself; do nothing (but a "failed" now is contradictory → human).
  * **Dead (failed/cancelled) but the partner says it holds money** → dangerous contradiction; a human must sort it out. We refuse to touch it.

**Posting the funding:**

```java
UUID clearing = accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_USD);
UUID partnerAccount = accounts.ensureSystemAccount(partnerAccountCode(r.getPartnerCode()), "USD");
ledger.post(PostingRequest.of(EntryType.REMITTANCE_FUNDING, r.getReference(), "Funding for remittance " + r.getReference(),
        new PostingLine(clearing, Money.ofMinor(r.getSellMinor(), USD)),
        new PostingLine(partnerAccount, Money.ofMinor(-r.getSellMinor(), USD))));
r.markFunded(clock.instant());
audit.move(r, RemittanceStatus.FUNDED, null);
```

* `clearing`: the dollar waiting room. `partnerAccount`: the box for "what *this partner* holds for us" (e.g. `PARTNER_SETTLEMENT_USD:fakepartner`).
* Two lines: waiting room **+1000.00**, partner box **−1000.00**. They add to zero. A negative partner box means "the partner is holding USD 1,000 on our behalf".
* The `reference` is the remittance reference, so posting this twice is impossible: the notebook refuses a second entry with the same (type, reference).
* Then record the time funded and step to `FUNDED`.

**Mismatch handling** (`flagMismatch`):

```java
if (r == null || r.getStatus() != RemittanceStatus.AWAITING_FUNDING || r.getFundingCaseId() != null) {
    return;     // nothing waiting, or already raised once
}
UUID caseId = compliance.openReviewCase(reference, "FUNDING", r.getSenderName(), "Funding mismatch: " + detail).id();
r.recordFundingCase(caseId);
audit.move(r, RemittanceStatus.UNDER_REVIEW, "Funding amount disagrees with the remittance");
```

* Only raise it once (`getFundingCaseId() != null` → already done), so a partner that keeps repeating the wrong amount doesn't loop us forever.
* Open a review case, remember it, move to `UNDER_REVIEW`.

## 5.6 When no message ever arrives: the safety net

**The picture.** If the London shop's phone call gets lost, we don't just wait for ever: every few seconds the engine *rings them* and asks.

```java
CorridorPartner.FundingStatus status = partner.fetchFunding(partnerCode, reference);
switch (status.state()) {
    case FUNDED -> { ... amount check ... funding.applyFunding(partnerCode, reference, true, ...); }
    case FAILED -> { funding.applyFunding(partnerCode, reference, false, ...); }
    case UNKNOWN -> { funding.expire(reference); return false; }
}
```

* `FUNDED`: same cross-check and the same `applyFunding` as the webhook. Because `applyFunding` is idempotent, webhook and safety-net **cannot conflict** (the test `askingThePartnerAfterTheWebhookDidArriveDoesNotFundTwice`).
* `FAILED`: apply the failure.
* `UNKNOWN`: nothing known yet. `expire` fails the remittance **only if** its 24-hour window has passed.
* If the partner can't be reached (`PartnerUnavailableException`), we catch it and do **nothing**: *"I couldn't reach them" is never the same as "they didn't pay".*

**A bug found here.** The first version looked only at the oldest 20 waiting remittances. A pile of old, quiet ones starved newer ones for ever. Now it pages through everything:

```java
for (int page = 0; page < 1_000; page++) {
    List<String> waiting = readOnly.execute(status -> funding.awaitingFundingOlderThan(cutoff, pageNumber, pageSize));
    if (waiting == null || waiting.isEmpty()) break;
    for (String reference : waiting) { ... checkFundingOf(reference) ... }
    if (waiting.size() < pageSize) break;
}
```

* Take page 0, then page 1, ... until a short or empty page. `1_000` is a safety stop so a bug can't loop forever.

---

# Part 6: Swap, pay, or refund (Step 22)

## 6.1 The engine and the steps

**The picture.** A conveyor belt with stations. Each station does **one small job**, writes a note, and moves the box on. If the power cuts, the notes tell you exactly which station the box is at, and you restart from there.

* `RemittanceProcessor` is the belt's motor. It's **not** a database transaction itself, because it talks to outside systems (the partner) and holding a database lock while waiting for the internet would be foolish.
* `RemittanceStepper` holds the stations. **Each station is one short transaction.**

Every station follows the same three-rule recipe:
1. **Lock** the remittance and check it is on the square this station expects (if not, quietly do nothing: someone else already did it).
2. **Post to the notebook** with a reference unique to this step (so repeating can't double-post).
3. **Move the square** and write the diary line, in the same transaction.

### Station 1: convert (FUNDED → CONVERTED)

```java
void convert(UUID id) {
    Remittance r = lock(id);
    if (r.getStatus() != RemittanceStatus.FUNDED) {
        return;
    }
    fx.convert(r.getQuoteId(), accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_USD),
            accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_NGN));
    audit.move(r, RemittanceStatus.CONVERTED, null);
    r.stepSucceeded();
}
```

* `lock(id)`: `SELECT ... FOR UPDATE` on the remittance.
* Wrong square? `return` (no error: this is exactly what happens when two engine threads race, and the second is simply late).
* `fx.convert`: the two-entry swap from Part 2.4, using the quote the remittance already used up. Because `fx.convert` is idempotent, a crash after posting but before the status change is harmless on retry (test `aConversionPostedJustBeforeACrashIsNotPostedAgain`).
* `stepSucceeded`: clear the "retry" bookkeeping.

### Station 2: pay (CONVERTED → PAID or PAYOUT_PENDING)

```java
if (r.getRecipientType() == RecipientType.PAYBRIDGE) {
    AccountSummary recipient = findUsableRecipient(r.getRecipientAccountNumber());
    if (recipient == null) { toRefund(r, "The recipient account can no longer receive naira"); return; }
    ledger.post(PostingRequest.of(EntryType.REMITTANCE_PAYOUT, r.getReference(), ...,
            new PostingLine(clearingNgn, Money.ofMinor(-r.getBuyMinor(), NGN)),
            new PostingLine(recipient.id(), Money.ofMinor(r.getBuyMinor(), NGN))));
    r.markPaid(clock.instant());
    audit.move(r, RemittanceStatus.PAID, null);
    events.publishEvent(new RemittancePaid(...));
    return;
}
String payoutReference = "PO-" + r.getReference();
payouts.request(new PayoutApi.PayoutRequest(payoutReference, clearingNgn, r.getBuyMinor(), ...));
r.recordPayoutReference(payoutReference);
audit.move(r, RemittanceStatus.PAYOUT_PENDING, null);
```

* **A PayBridge recipient:** look the account up again (it might have been frozen since creation; if so, refund). Move naira out of the naira waiting room into the recipient's account. Mark `PAID` and announce it (the notification module then tells Mum).
* **A recipient at another bank:** we can't just credit them. We hand a payout request to the `transfer` module (`PayoutApi`) with a reference `PO-<remittance reference>`, and wait in `PAYOUT_PENDING`.

### Station 3: check the payout (PAYOUT_PENDING)

```java
switch (payout.get().status()) {
    case SUCCESS -> { r.markPaid(...); audit.move(r, PAID, null); events.publishEvent(new RemittancePaid(...)); }
    case FAILED -> toRefund(r, "The payout failed: " + payout.get().failureReason());
    case PENDING, PROCESSING -> r.waitUntil(clock.instant().plus(properties.processor().payoutPollDelay()));
}
```

* Success → `PAID`. Failure → go to the refund lane. Still going → look again in a few seconds (don't hammer).
* Important: the remittance never refunds while the payout outcome is *unknown*. We only refund on a definite `FAILED`.

### Station 4: refund (REFUND_PENDING → REFUNDED)

```java
if (fx.findConversion(r.getQuoteId()).filter(c -> !c.reversed()).isPresent()) {
    fx.reverseConversion(r.getQuoteId(), reason);
}
PostedEntry funding = ledger.find(EntryType.REMITTANCE_FUNDING, r.getReference()).orElseThrow(...);
ledger.reverse(funding.id(), "refund:" + r.getReference(), "Refund: " + reason);
gate.releaseLimits(r);
audit.move(r, RemittanceStatus.REFUNDED, reason);
```

* If a conversion happened, undo **both legs**.
* Find the original funding entry and **reverse** it: the dollars go back from the waiting room to the partner's box (so we no longer "hold" them for Tunde; the partner will return them to Tunde).
* Give back the sender's allowance.
* Mark `REFUNDED`.
* Nothing is erased: each undo is a **new** opposite entry. The test `aPayoutTheProviderRefusesIsRefundedInFull` snapshots **every** related balance before and after and checks they are identical.

### When a station keeps failing

```java
void recordFailure(UUID id, String error) {
    ...
    r.stepFailed(error, clock.instant().plus(delay));
    if (attempt >= properties.processor().maxAttempts() && (status == FUNDED || status == CONVERTED)) {
        toRefund(r, "The remittance could not be processed: " + error);
    }
}
```

* Each failure schedules a later retry with a growing wait (`baseDelay`, doubling, capped at 5 minutes).
* After 5 failed tries, a **funded** remittance is sent to refund instead of being stuck holding someone's money. (Test: freeze the naira box so conversion keeps failing; it ends `REFUNDED` with all balances restored.)

## 6.2 The motor: RemittanceProcessor

```java
public int runOnce() {
    int done = resolveReviews();
    done += checkFunding();
    done += step();
    return done;
}
```

* Three jobs per pass: (1) re-check reviews decided elsewhere, (2) ask the partner about unfunded remittances, (3) move funded remittances one station.
* `step()` looks up which remittances are **due** (funded-and-later states whose retry time has arrived), reads each one's status, and calls the matching station. If a station throws, it's caught and `recordFailure` is called in a separate transaction: **one broken remittance never stops the others.**
* `drain(maxPasses)` repeats `runOnce` until a pass does nothing, so a funded remittance races to the end quickly. The scheduled timer (`RemittanceSchedule`) calls it every 3 seconds. In tests the timer is off and tests call the engine by hand, so they know exactly what happened.
* Remittances `UNDER_REVIEW` or `AWAITING_FUNDING` are never picked by `step()`: the list of "stepped" statuses is exactly `FUNDED, CONVERTED, PAYOUT_PENDING, REFUND_PENDING`. **Compliance really does gate the flow.**

## 6.3 Paying someone at another bank: SystemPayoutService

**The picture.** The existing external-transfer machine assumes the money comes from a **customer's** piggy bank (it checks ownership, applies customer limits, charges a fee). Here the money comes from the **bank's own waiting room**. Rather than risk breaking the delicate old machine, we built a small sister that shares the same ideas.

The ledger dance is identical to the old one, using the `SUSPENSE` "in transit" box:

```
request:   source box -amount      SUSPENSE +amount          (EXTERNAL_HOLD)
success:   SUSPENSE -amount        PAYSTACK_SETTLEMENT +amount   (EXTERNAL_SETTLEMENT)
failure:   the hold is reversed: money returns to the source box
```

**Requesting (idempotent):**

```java
int inserted = jdbc.update("""
        insert into system_payout (...) values (...)
        on conflict (reference) do nothing
        """, ...);
Row row = load(request.reference()).orElseThrow();
if (inserted == 0) {
    if (row.amountMinor() != request.amountMinor() || !row.accountNumber().equals(request.accountNumber()) ...) {
        throw new PayBridgeException(ErrorCode.CONFLICT, "Payout ... already exists with different details");
    }
    return view(row);
}
... lock source, post the EXTERNAL_HOLD ...
```

* Same reference + same details → return the existing payout (nothing double-held).
* Same reference + **different** details → refuse (`CONFLICT`).
* New → record it, then hold the money.

**The dispatcher** (`SystemPayoutDispatcher`) is the postman and the detective in one class, and — like the old one — it is **not** transactional: slow calls to the bank happen with no database lock held. Its core:

```java
try {
    String recipient = gateway.createRecipient(d.bankCode(), d.accountNumber(), d.accountName());
    result = gateway.initiateTransfer(new TransferInstruction(d.reference(), d.amountMinor(), d.currency(), recipient, d.narration()));
} catch (GatewayUnavailableException e) {
    service.scheduleRetryOrGiveUp(reference, e.getMessage());   // unknown outcome: never treat as a failure
    return;
}
```

* A timeout means *we don't know* whether the bank got it. We **never** refund on a timeout; we retry (the bank ignores a repeated reference), and after too many tries the detective (`reconcile`) asks the bank directly.

**Contradictions go to a human:** a "success" after we already refunded, or a "failure" after success, throw `INVALID_STATE`, which the webhook pipeline records as `MANUAL_REVIEW`. Test `aSuccessThatArrivesAfterTheRefundGoesToManualReviewAndChangesNothing` checks nothing changes.

**The same starvation fix as before**: `reconcile()` pages through *all* quiet payouts rather than only the oldest 20, so payouts the fake bank keeps "pending" can't crowd out the rest.

---

# Part 7: Comparing notebooks (settlement, Steps 24 to 26)

## 7.1 The picture

At the end of the day PayBridge and the London shop each have a notebook of the same events. They rarely agree perfectly. So:
1. We **tear out a page** of our notebook (a **batch**) and freeze it.
2. The shop sends **their** page (the **statement**).
3. We **line them up** line by line (**matching**).
4. Every line that doesn't match becomes a **break** that someone must explain.
5. The shop **pays what it owes** (the **settlement payment**).

## 7.2 Tearing out a page: BatchService.close

**Why.** Without frozen pages we'd be comparing a notebook that keeps changing while we read it.

**The rule that matters most:** every line in the partner's box belongs to **exactly one** page. The database enforces it:

```sql
CONSTRAINT uq_settlement_batch_line_ledger UNIQUE (ledger_line_id)
```

* `ledger_line_id` is the id of a line in the ledger. `UNIQUE` means the same line cannot be copied into two batches, ever. Even a bug cannot double-count.

**The code** (shortened):

```java
jdbc.queryForObject("select code from settlement_partner where code = ? for update", String.class, code);

Optional<BatchView> existing = jdbc.query("select * from settlement_batch where partner_code = ? and cutoff_at = ?", ...);
if (existing.isPresent()) return existing;

Set<UUID> alreadyBatched = new HashSet<>(jdbc.queryForList(
        "select bl.ledger_line_id from settlement_batch_line bl join settlement_batch b on b.id = bl.batch_id where b.partner_code = ?", ...));
List<AccountLineView> fresh = ledger.linesBefore(accountId, cutoff, MAX_LINES_PER_BATCH).stream()
        .filter(line -> !alreadyBatched.contains(line.lineId())).toList();
if (fresh.isEmpty()) return Optional.empty();
```

**Line by line.**
1. `... for update` on the partner row: only **one** closer per partner at a time. A second one waits, then finds the page already made.
2. Same cut-off already closed? Return that batch. *Closing twice is a no-op.* (A `UNIQUE (partner_code, cutoff_at)` also backs this up.)
3. `alreadyBatched`: all the ledger lines this partner has already put on earlier pages.
4. `ledger.linesBefore(...)`: every line on the partner's box created before the cut-off, oldest first.
5. `.filter(!alreadyBatched)`: keep only the **new** ones.
6. Nothing new? No batch (no empty pages).

```java
long opening = <previous batch's closing, or 0>;
long movements = fresh.stream().mapToLong(AccountLineView::amountMinor).sum();
... insert into settlement_batch (... opening_minor, movements_minor, closing_minor ...) values (..., opening, movements, opening + movements, ...)
jdbc.batchUpdate("insert into settlement_batch_line ...", fresh, 500, ...);
```

* `opening` = where the last page ended. `movements` = the sum of this page's lines. `closing = opening + movements` (a database `CHECK` guarantees this).
* The lines are copied into `settlement_batch_line` in groups of 500. This table has a "no editing, no deleting" trigger: a frozen page stays frozen.

**A late line is never lost.** A posting that was still being saved when we closed has a timestamp before the cut-off but isn't visible yet. It simply isn't in `fresh`; the **next** close picks it up because it is "new and not batched". The concurrency test `postingsRacingTheClosersNeitherLoseNorDoubleCountALine` fires fundings and closers together and checks each line landed on exactly one page.

## 7.3 Paying: instruct and confirm

* `instruct`: CLOSED → INSTRUCTED ("we've asked the partner to pay"). Repeating it changes nothing.
* `confirm`: INSTRUCTED → CONFIRMED: posts **one** payment entry.

```java
long net = netToSettle(batchId);
if (net != 0) {
    PostedEntry entry = ledger.post(PostingRequest.of(EntryType.SETTLEMENT_PAYMENT,
            "SETTLE-" + batch.partnerCode().toUpperCase(Locale.ROOT) + "-" + batch.sequence(), ...,
            new PostingLine(partnerAccount, Money.ofMinor(-net, currency)),
            new PostingLine(cash, Money.ofMinor(net, currency))));
}
jdbc.update("update settlement_batch set status = 'CONFIRMED', ... where id = ? and status = 'INSTRUCTED'", ...);
```

**Line by line.**
* `net`: how much this page obliges the partner to pay. If funding made the partner's box −1,600, `net` is −1,600.
* Post an entry that moves the partner's box by `−net` (bringing it back to 0) and a mirror `SETTLEMENT_CASH_USD` box by `+net` (our bank received the cash). The two lines add to zero.
* The reference `SETTLE-FAKEPARTNER-3` includes the batch number, so it can only be posted once.
* `where ... status = 'INSTRUCTED'`: the status flip is conditional, so a repeat does nothing.
* A funding and its refund net to 0 → **no entry at all** (nothing to pay).

**The sneaky double-payment bug.** After a payment is posted, its own line sits in the partner's box and is swept into the **next** page. If we summed the next page naively we'd "pay" last time's payment again. So:

```java
public long netToSettle(UUID batchId) {
    ... "select coalesce(sum(amount_minor), 0) from settlement_batch_line where batch_id = ? and entry_type <> 'SETTLEMENT_PAYMENT'" ...
}
```

* `entry_type <> 'SETTLEMENT_PAYMENT'`: **ignore old payments** when working out what is owed. A payment settles an obligation already counted on an earlier page. The test `anEarlierPaymentIsNeverPaidAgainByTheNextBatch` locks this in.

## 7.4 Reading the partner's page: StatementParser

**The picture.** A teacher marking a homework page. Forgiving about neatness, strict about wrong answers. If **any** answer is wrong, send the *whole* page back (don't half-mark it).

```java
if (text.startsWith("﻿")) { text = text.substring(1); }
String[] lines = text.split("\\r?\\n", -1);
...
if (!lines[headerLine].replace(" ", "").toLowerCase(Locale.ROOT).equals(HEADER)) { ... error ... }
```

* Strip an invisible byte-order mark; accept Windows or Linux line endings; ignore blank lines and spacing; the header may be any letter case. (**Forgiving**.)
* But the header must be exactly `reference,amount,currency,date`.

```java
if (!AMOUNT.matcher(amountText).matches()) {
    addError(errors, rowNumber, "the amount must be a whole number of minor units (no decimal point)");
}
...
if (!currency.equals(expectedCurrency)) { addError(... "the currency must be " + expectedCurrency); }
```

* An amount like `12.50` is **refused**, not rounded (we never guess about money). A wrong currency is refused. A bad date is refused. (**Strict**.)
* Every problem is reported with its **row number**, capped at 20 messages so a huge bad file doesn't flood the reply. If there is any error, the result is invalid and **nothing is imported**.

## 7.5 Lining up the pages: StatementMatcher

**The picture.** Two stacks of cards. First pair up cards that have the **same label and the same number**. Then handle leftovers more cleverly.

Three passes, then leftovers:

**Pass 1a: same label, same amount** (within a 1-cent tolerance):

```java
long gap = Math.abs(o.amount() - t.amount());
if (gap <= amountTolerance && (best == null || gap < Math.abs(best.amount() - t.amount()))) { best = o; }
```
* Among our unused lines with the same reference, take the one whose amount is closest, if it is within tolerance. Tolerance exists so a one-cent rounding difference doesn't need a human.

**Pass 1b: same label, different amount** → **AMOUNT_DIFFERS** (a pair), done *after* all exact matches so a duplicated line can't steal a good partner. A label we've already matched appearing again → "Duplicate line". A label we've never heard of → "not in our records".

**Pass 2: no label**, so match by **amount and date** (within 1 day), choosing the nearest date.

**Pass 3: no label, groups.** A partner often sends one total for several payments. We try combinations of 2 to 4 of our lines that add up to their total:

```java
private static List<Ours> search(List<Ours> pool, int from, int left, long sum, long target, long tolerance, List<Ours> chosen) {
    if (left == 0) { return Math.abs(sum - target) <= tolerance ? new ArrayList<>(chosen) : null; }
    for (int i = from; i <= pool.size() - left; i++) {
        chosen.add(pool.get(i));
        List<Ours> found = search(pool, i + 1, left - 1, sum + pool.get(i).amount(), target, tolerance, chosen);
        chosen.remove(chosen.size() - 1);
        if (found != null) return found;
    }
    return null;
}
```
* Classic "try picking this card, then recursively pick the rest; if it doesn't work, put it back". The pool is capped at 25 lines so it can't explode.

**Leftovers:** ours with no partner → `UNMATCHED_OURS`; theirs with no partner → `UNMATCHED_THEIRS`. **Every line on both sides ends in exactly one outcome**; a test asserts the counts add up.

**One tricky detail.** A refund's reference in our notebook is `refund:RM-123`, but the partner just writes `RM-123`. `StatementService.normalise` strips the `refund:` so they match. And amounts are flipped to the partner's point of view (what they *hold for us* is positive): `ourAmount = −ledgerAmount`.

## 7.6 Loading the statement: StatementService.upload

```java
BatchView batch = batches.lock(batchId);          // one upload at a time per batch
String fingerprint = sha256(content);
...
if (earlier.isPresent()) {
    if (earlier.get().get("fingerprint").equals(fingerprint)) return summarise(..., true);   // same file: no-op
    throw new PayBridgeException(ErrorCode.CONFLICT, "This batch already has a different statement loaded");
}
StatementParser.Result parsed = StatementParser.parse(content, maxRows, batch.currency());
if (!parsed.isValid()) { throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The statement was not imported. " + ...); }
```

* `sha256(content)`: a short fingerprint of the file. The **same** file twice has the same fingerprint → no-op (reply `200` with `duplicate: true`). A **different** file for the same batch → refuse.
* Invalid file → refuse, **nothing stored**.
* Then the matcher runs, each statement line is saved with its result, and each difference becomes a **break** row.
* Finally `reconcileIfClean` runs (see 7.8).

## 7.7 Breaks: BreakService.resolve

**The picture.** A "mismatch" sticker on a page. Someone must peel it off **and write why**.

```java
int claimed = jdbc.update("""
        update settlement_break set status = 'RESOLVED', resolution_note = ?, resolved_by = ?, resolved_at = now()
        where id = ? and status = 'OPEN'
        """, note.trim(), resolvedBy, breakId);
if (claimed == 0) {
    throw new PayBridgeException(ErrorCode.INVALID_STATE, "This break was already resolved");
}
```
* The biscuit rule again: only the first resolver wins; a resolved break can't be resolved twice. The note is mandatory (checked before, and by a database rule).

**Correcting the money** (`adjust = true`):

```java
long correction = ours - theirs;
...
ledger.post(PostingRequest.of(EntryType.SETTLEMENT_ADJUSTMENT, "ADJ-" + breakId, ...,
        new PostingLine(partnerAccount, Money.ofMinor(correction, currency)),
        new PostingLine(adjustment, Money.ofMinor(-correction, currency))));
```

* If we recorded 100,000 and the partner says 99,000, `correction = 1,000`: move the partner's box by that, against a `SETTLEMENT_ADJUSTMENT_USD` box. Two lines, zero sum: the books still balance.
* Same reference `ADJ-<break id>` → can't post twice.
* If there's no money difference, an adjustment is refused ("resolve with a note only").

## 7.8 Reaching "RECONCILED"

```java
update settlement_batch b set status = 'RECONCILED', reconciled_at = now()
where b.id = ? and b.status in ('CONFIRMED', 'DISPUTED')
  and exists (select 1 from settlement_statement s where s.batch_id = b.id)
  and not exists (select 1 from settlement_break k where k.batch_id = b.id and k.status = 'OPEN')
```

A batch is **reconciled** only when all three are true: it has been **paid**, a **statement** was loaded, and **no break is still open**. It runs after the statement loads, after payment, and after each break is resolved, so whichever happens last finishes the job. Running it again changes nothing. `DISPUTED` is a note-bearing parking state for "paid, but we're arguing about the breaks"; it can still become reconciled later.

## 7.9 The report

`BatchService.report` gathers opening, movements, closing, **net to settle**, how many statement lines matched, open and resolved breaks, **open breaks by age** (0-1 days, 2-7 days, over 7 days: old ones are the ones to chase), and `tracesToLedger`: whether the closing figure equals the sum of every ledger line before the cut-off (every number can be traced back to the notebook).

---

# Part 8: How we know it works (the tests)

* **Pure maths tests** (no database): the calculator, name similarity, screening tiers, status table, statement parser, matcher. Fast and exhaustive.
* **Database tests with a real Postgres** (Testcontainers): each flow end to end.
* **Race tests**: ten threads tear one quote; twelve payments race one allowance; six fundings race the closers; eight conversions share the same boxes. A `CountDownLatch` is a starting gun so they all go at the same instant.
* **Crash-style tests**: run only one engine pass, then another ("a restarted engine"); post a conversion by hand then run the engine.
* **Balance snapshots**: take every relevant balance before, run a scenario (e.g. a full refund), take them after, assert they are **identical**.
* **The zero-sum check** after nearly every test.
* **A parallel load test**: 24 remittances created, funded and paid at once, mixing instant success and refusals; afterwards each quote was used once, no customer balance is negative, and recipients hold exactly what they were owed.
* **Total: 841 tests, all passing.**

---

# Part 9: Explaining it to a 5-year-old (your cheat sheet)

> "Tunde in London gives dollars to a shop. The shop tells our bank, using a secret seal so nobody can lie. We swap the dollars for naira at a price we promised him for a few minutes. We put the naira in his mum's piggy bank. If anything goes wrong, we give his dollars back. Every coin we move is written in a notebook that must always add up to zero, and we never rub anything out. A doorman checks nobody on a bad-people list is involved, and if he isn't sure, a grown-up decides before anything moves. At night we compare our notebook with the shop's, circle every difference, and don't stop until each one is explained."

| Grown-up word | 5-year-old version |
|---|---|
| `idempotent` | pressing the lift button twice still brings one lift |
| `race condition` | two kids grabbing the last biscuit |
| `ON CONFLICT DO NOTHING` | "if it's already taken, leave it alone" |
| `UPDATE ... WHERE status='OPEN'` | tear the ticket only if it's still whole |
| `transaction` | "all or nothing": finish the whole drawing or throw it away |
| `lock / FOR UPDATE` | a "do not disturb" sign on the door |
| `deadlock` | two kids each holding one crayon waiting for the other's |
| `append-only` / trigger | write in pen; a guard stops anyone using an eraser |
| `webhook` + `HMAC` | a phone call stamped with a secret wax seal |
| `state machine` | a board game where you may only step to certain squares |
| `double-entry ledger` | every move is written twice (out here, in there) and must add to zero |
| `conversion` | two drawers: dollars go in one, naira come out of the other |
| `reconciliation` | two friends comparing notebooks and explaining each difference |
| `break` | a "mismatch" sticker someone must explain |

---

# Appendix: what differs from the original plan

* The link between the two conversion entries is a table (`fx_conversion`), not a ledger column.
* The quote is used up at remittance **creation**, not after funding; the 24-hour funding window limits the wait.
* New status `AWAITING_FUNDING` (and `AWAITING_FUNDING → UNDER_REVIEW` for a funding mismatch).
* System payouts are a separate small service rather than changes to `ExternalTransferService` (so its 28 tests stayed untouched).
* Not built: real watch-list import, a retention period for cases (an open question), a `PARTNER` API role, revaluation of the house's FX position, and any real provider.
