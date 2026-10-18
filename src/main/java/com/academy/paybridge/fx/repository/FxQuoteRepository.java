package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.FxQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface FxQuoteRepository extends JpaRepository<FxQuote, UUID> {

    Optional<FxQuote> findByRequestedByAndIdempotencyKey(String requestedBy, String idempotencyKey);

    boolean existsByConsumedBy(UUID consumedBy);

    /**
     * Issues the quote unless this requester already used this idempotency key. Returns 1 if this call issued it, 0 if the key was
     * already used. The expiry is worked out by the DATABASE clock, the same clock {@link #consume} checks against.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            insert into fx_quote (id, reference, corridor, sell_minor, fee_minor, buy_minor, mid_rate, customer_rate, spread_bps,
                                  rate_source, rate_as_of, expires_at, status, requested_by, idempotency_key, request_hash, created_at)
            values (:id, :reference, :corridor, :sell, :fee, :buy, :mid, :customerRate, :spreadBps,
                    :rateSource, :rateAsOf, now() + (:ttlSeconds * interval '1 second'), 'OPEN', :requestedBy, :idempotencyKey,
                    :requestHash, now())
            on conflict (requested_by, idempotency_key) do nothing
            """, nativeQuery = true)
    int issueIfAbsent(@Param("id") UUID id,
                      @Param("reference") String reference,
                      @Param("corridor") String corridor,
                      @Param("sell") long sell,
                      @Param("fee") long fee,
                      @Param("buy") long buy,
                      @Param("mid") BigDecimal mid,
                      @Param("customerRate") BigDecimal customerRate,
                      @Param("spreadBps") int spreadBps,
                      @Param("rateSource") String rateSource,
                      @Param("rateAsOf") Instant rateAsOf,
                      @Param("ttlSeconds") long ttlSeconds,
                      @Param("requestedBy") String requestedBy,
                      @Param("idempotencyKey") String idempotencyKey,
                      @Param("requestHash") String requestHash);

    /**
     * Uses the quote, in ONE statement that checks it is still open and not expired. Returns 1 if this call used it, otherwise 0.
     * Two simultaneous calls cannot both get 1: the row is locked by the first, and the second then finds it no longer open.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            update fx_quote
               set status = 'CONSUMED', consumed_by = :consumer, consumed_at = now()
             where id = :id and status = 'OPEN' and expires_at > now()
            """, nativeQuery = true)
    int consume(@Param("id") UUID id, @Param("consumer") UUID consumer);
}
