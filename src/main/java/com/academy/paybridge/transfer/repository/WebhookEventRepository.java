package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    /**
     * Records that we have received this event. Returns 1 if this call recorded it, 0 if the same event (same provider
     * and event id) was already on file. If two identical events arrive at the same instant, one waits for the other to
     * commit and then gets 0, so exactly one caller ever gets 1.
     * <p>
     * The casts let a null value through: Postgres cannot work out the type of a bare null parameter.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            insert into webhook_event (id, provider, event_id, event_type, reference, outcome, amount_minor,
                                       status, payload_sha256, attempts, created_at, updated_at)
            values (gen_random_uuid(), :provider, :eventId, :eventType, cast(:reference as varchar),
                    cast(:outcome as varchar), cast(:amountMinor as bigint), 'RECEIVED', :payloadSha256, 1, now(), now())
            on conflict (provider, event_id) do nothing
            """, nativeQuery = true)
    int claim(@Param("provider") String provider,
              @Param("eventId") String eventId,
              @Param("eventType") String eventType,
              @Param("reference") String reference,
              @Param("outcome") String outcome,
              @Param("amountMinor") Long amountMinor,
              @Param("payloadSha256") String payloadSha256);

    Optional<WebhookEvent> findByProviderAndEventId(String provider, String eventId);

    /**
     * Takes over an event for another try, but only if it failed last time, or was claimed so long ago
     * ({@code staleBefore}) that whoever claimed it must have crashed. Returns 1 if we now own it.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            update webhook_event
               set status = 'RECEIVED', attempts = attempts + 1, updated_at = now()
             where id = :id
               and (status = 'FAILED' or (status = 'RECEIVED' and updated_at < :staleBefore))
            """, nativeQuery = true)
    int retake(@Param("id") UUID id, @Param("staleBefore") Instant staleBefore);
}
