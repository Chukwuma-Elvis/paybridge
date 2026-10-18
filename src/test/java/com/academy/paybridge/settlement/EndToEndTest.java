package com.academy.paybridge.settlement;

import com.academy.paybridge.remittance.api.CreateRemittance;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Step 27: whole journeys, and a parallel run that checks every invariant at the end. */
class EndToEndTest extends AbstractSettlementTest {

    @Test
    void theWholeJourneyFromQuoteToReconciledSettlement() throws Exception {
        String account = newAccountNumber();
        RemittanceView view = createClean(paybridgeRecipient(account));
        fundingWebhook(view, SELL);
        runEngine();
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAID);
        assertThat(balanceOfNumber(account)).isEqualTo(BUY);

        BatchView batch = closeNow();
        statements.upload(batch.id(), bytes(exactStatement(batch)), "tester");
        batches.instruct(batch.id());

        assertThat(batches.confirm(batch.id()).status()).isEqualTo("RECONCILED");
        assertThat(partnerBalance()).isZero();
    }

    @Test
    void aRefundedRemittanceStillSettlesCleanlyWithTheStatementShowingBothMovements() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Chidi Okafor", "NG", bankRecipient("1111"))).remittance();
        fundingWebhook(view, SELL);
        runEngine();
        payoutDispatcher.dispatch("PO-" + view.reference());
        runEngine();
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.REFUNDED);

        BatchView batch = closeNow();
        var result = statements.upload(batch.id(), bytes(exactStatement(batch)), "tester");

        assertThat(result.lineCount()).isEqualTo(2);
        assertThat(result.breaksOpen()).isZero();
    }

    @Test
    void manyRemittancesInParallelAllReachAFinalStateAndEveryInvariantHolds() throws Exception {
        int total = 24;
        List<String> internalAccounts = new ArrayList<>();
        List<CreateRemittance> commands = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            CreateRemittance.Recipient recipient;
            if (i % 3 == 0) {
                recipient = bankRecipient(i % 6 == 0 ? "3333" : "1111");     // paid at once, or refused (so refunded)
            } else {
                String account = newAccountNumber();
                internalAccounts.add(account);
                recipient = paybridgeRecipient(account);
            }
            commands.add(command(newQuote(50_000 + i * 1_000L), "Chidi Okafor", "NG", recipient));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        // 1. create them all at once
        List<RemittanceView> created = runAll(pool, commands.stream().<Callable<RemittanceView>>map(c -> () -> create(c).remittance()).toList());
        assertThat(created).extracting(RemittanceView::status).containsOnly(RemittanceStatus.AWAITING_FUNDING);

        // 2. the partner confirms them all at once
        runAll(pool, created.stream().<Callable<String>>map(v -> () -> fundingWebhook(v, v.sellMinor())).toList());

        // 3. several engines and the payout postman all work at the same time
        List<Callable<Integer>> workers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            workers.add(() -> processor.drain(20));
        }
        workers.add(() -> payoutDispatcher.dispatchDue());
        runAll(pool, workers);
        for (int i = 0; i < 4; i++) {
            payoutDispatcher.dispatchDue();
            runEngine();
        }
        pool.shutdown();

        // every remittance ended as PAID or REFUNDED
        for (RemittanceView view : created) {
            assertThat(statusOf(view)).as(view.reference()).isIn(RemittanceStatus.PAID, RemittanceStatus.REFUNDED);
        }
        long paid = created.stream().filter(v -> statusOf(v) == RemittanceStatus.PAID).count();
        long refunded = total - paid;
        assertThat(refunded).as("the 1111 bank payouts were refused and refunded").isEqualTo(created.stream()
                .filter(v -> v.recipientAccountMasked().endsWith("1111")).count());

        // every quote used exactly once, every conversion made once, nothing posted twice
        for (RemittanceView view : created) {
            assertThat(countRows("select count(*) from fx_quote where id = ? and status = 'CONSUMED' and consumed_by = ?", view.quoteId(), view.id()))
                    .isEqualTo(1);
            assertThat(countRows("select count(*) from fx_conversion where quote_id = ?", view.quoteId())).isEqualTo(1);
            assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isEqualTo(1);
        }
        // no customer account below zero, and the recipients hold exactly what they were owed
        assertThat(countRows("select count(*) from account where account_type = 'CUSTOMER' and balance_minor < 0")).isZero();
        long expectedInternal = created.stream().filter(v -> !v.recipientAccountMasked().endsWith("3333") && !v.recipientAccountMasked().endsWith("1111"))
                .mapToLong(RemittanceView::buyMinor).sum();
        long actualInternal = internalAccounts.stream().mapToLong(this::balanceOfNumber).sum();
        assertThat(actualInternal).isEqualTo(expectedInternal);
        // I1 (each currency sums to zero) is checked after every test by the base class
    }

    private <T> List<T> runAll(ExecutorService pool, List<Callable<T>> jobs) throws Exception {
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> job : jobs) {
            futures.add(pool.submit(() -> {
                gun.await();
                return job.call();
            }));
        }
        gun.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(120, TimeUnit.SECONDS));
        }
        return results;
    }
}
