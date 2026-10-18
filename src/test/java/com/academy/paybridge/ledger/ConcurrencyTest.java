package com.academy.paybridge.ledger;

import com.academy.paybridge.LedgerInvariants;
import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many transfers at the same instant. These tests exist because concurrency bugs never show up one request at a time.
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    LedgerApi ledgerApi;

    @Autowired
    AccountApi accountApi;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private record Outcome(int succeeded, int insufficientFunds, List<Throwable> unexpected) {
    }

    private PostedEntry transfer(UUID from, UUID to, long kobo, String reference) {
        return ledgerApi.post(PostingRequest.of(EntryType.TRANSFER, reference, "concurrent test",
                new PostingLine(from, Money.ngn(-kobo)),
                new PostingLine(to, Money.ngn(kobo))));
    }

    private long balance(UUID accountId) {
        return accountApi.getAccount(accountId).balanceMinor();
    }

    private void assertCachedBalancesMatchTheLedger(UUID... accountIds) {
        for (UUID id : accountIds) {
            assertThat(balance(id)).as("cached balance of %s", id).isEqualTo(ledgerApi.sumOfLines(id));
        }
    }

    /** Releases all tasks at the same moment from a pool of threads and reports what happened to each. */
    private Outcome runAtTheSameTime(int threads, List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startingGun = new CountDownLatch(1);
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                Callable<Throwable> wrapped = () -> {
                    startingGun.await();
                    try {
                        task.run();
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                };
                futures.add(pool.submit(wrapped));
            }
            startingGun.countDown();

            int succeeded = 0;
            int insufficient = 0;
            List<Throwable> unexpected = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                // a deadlock would show up here as a timeout
                Throwable failure = future.get(90, TimeUnit.SECONDS);
                if (failure == null) {
                    succeeded++;
                } else if (failure instanceof InsufficientFundsException) {
                    insufficient++;
                } else {
                    unexpected.add(failure);
                }
            }
            return new Outcome(succeeded, insufficient, unexpected);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyTransfersFromOneAccountNeverOverdrawIt() throws Exception {
        UUID source = newFundedAccount(10_000);
        UUID destination = newAccount();

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tasks.add(() -> transfer(source, destination, 300, "race-" + UUID.randomUUID()));
        }
        Outcome outcome = runAtTheSameTime(8, tasks);

        // 10,000 pays for exactly 33 transfers of 300 (9,900), leaving 100: the other 7 must be refused.
        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(33);
        assertThat(outcome.insufficientFunds()).isEqualTo(7);
        assertThat(balance(source)).isEqualTo(100);
        assertThat(balance(destination)).isEqualTo(9_900);
        assertCachedBalancesMatchTheLedger(source, destination);
    }

    @Test
    void transfersInOppositeDirectionsNeverDeadlock() throws Exception {
        UUID a = newFundedAccount(100_000);
        UUID b = newFundedAccount(100_000);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean aToB = i % 2 == 0;
            tasks.add(() -> {
                if (aToB) {
                    transfer(a, b, 100, "ab-" + UUID.randomUUID());
                } else {
                    transfer(b, a, 100, "ba-" + UUID.randomUUID());
                }
            });
        }
        Outcome outcome = runAtTheSameTime(8, tasks);

        assertThat(outcome.unexpected()).as("no deadlocks, no other failures").isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(200);
        assertThat(balance(a)).isEqualTo(100_000);
        assertThat(balance(b)).isEqualTo(100_000);
        assertCachedBalancesMatchTheLedger(a, b);
    }

    @Test
    void manyAccountsPayingOneBusyAccountAllArriveExactlyOnce() throws Exception {
        UUID busy = newAccount();
        List<UUID> sources = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sources.add(newFundedAccount(5_000));
        }

        List<Runnable> tasks = new ArrayList<>();
        for (UUID source : sources) {
            tasks.add(() -> transfer(source, busy, 1_000, "fan-in-" + UUID.randomUUID()));
        }
        Outcome outcome = runAtTheSameTime(8, tasks);

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(20);
        assertThat(balance(busy)).isEqualTo(20_000);
        for (UUID source : sources) {
            assertThat(balance(source)).isEqualTo(4_000);
        }
        assertCachedBalancesMatchTheLedger(busy);
    }

    @Test
    void theSameEventPostedByManyThreadsAtOnceMovesMoneyOnlyOnce() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();
        String sharedReference = "same-event-" + UUID.randomUUID();

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> transfer(from, to, 2_000, sharedReference));
        }
        Outcome outcome = runAtTheSameTime(8, tasks);

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(12); // every caller is told "done"...
        assertThat(balance(from)).isEqualTo(8_000);    // ...but the money moved once
        assertThat(balance(to)).isEqualTo(2_000);
        Integer entries = jdbcTemplate.queryForObject(
                "select count(*) from journal_entry where entry_type = 'TRANSFER' and reference = ?",
                Integer.class, sharedReference);
        assertThat(entries).isEqualTo(1);
    }

    @Test
    void afterAllThatTheBankStillAddsUpToZero() {
        LedgerInvariants.everyCurrencySumsToZero(jdbcTemplate);
    }
}
