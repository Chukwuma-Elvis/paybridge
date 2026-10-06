package com.academy.paybridge.transfer;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.transfer.service.InternalTransferCommand;
import com.academy.paybridge.transfer.service.TransferResult;
import com.academy.paybridge.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classic double-click: the very same request, with the very same Idempotency-Key, arrives many times at once.
 */
class DuplicateRequestRaceTest extends AbstractIntegrationTest {

    @Autowired
    TransferService transferService;

    @Autowired
    AccountApi accountApi;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void manySimultaneousIdenticalRequestsCreateExactlyOneTransfer() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();
        String destinationNumber = accountApi.getAccount(to).accountNumber();
        String key = "race-" + UUID.randomUUID();
        InternalTransferCommand command = new InternalTransferCommand(from, destinationNumber, 2_500, "double click");

        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<TransferResult> results = new ArrayList<>();
        try {
            CountDownLatch startingGun = new CountDownLatch(1);
            List<Future<TransferResult>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Callable<TransferResult> task = () -> {
                    startingGun.await();
                    return transferService.transferInternal(command, key);
                };
                futures.add(pool.submit(task));
            }
            startingGun.countDown();
            for (Future<TransferResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long created = results.stream().filter(result -> !result.replayed()).count();
        Set<UUID> transferIds = new HashSet<>();
        results.forEach(result -> transferIds.add(result.transfer().id()));

        assertThat(created).as("exactly one request does the work").isEqualTo(1);
        assertThat(transferIds).as("everyone is told about the same transfer").hasSize(1);
        assertThat(accountApi.getAccount(from).balanceMinor()).isEqualTo(7_500);
        assertThat(accountApi.getAccount(to).balanceMinor()).isEqualTo(2_500);
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from transfer where source_account_id = ?", Integer.class, from);
        assertThat(rows).isEqualTo(1);
    }
}
