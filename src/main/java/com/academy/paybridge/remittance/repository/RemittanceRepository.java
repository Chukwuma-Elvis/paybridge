package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.domain.Remittance;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RemittanceRepository extends JpaRepository<Remittance, UUID> {

    Optional<Remittance> findByReference(String reference);

    boolean existsByPartnerCodeAndPartnerReference(String partnerCode, String partnerReference);

    Optional<Remittance> findByClientNameAndIdempotencyKey(String clientName, String idempotencyKey);

    List<Remittance> findBySenderKeyOrderByCreatedAtDesc(String senderKey, Pageable page);

    /** SELECT ... FOR UPDATE: whoever works on a remittance holds it alone until their transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Remittance r where r.id = :id")
    Optional<Remittance> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Remittance r where r.reference = :reference")
    Optional<Remittance> findByReferenceForUpdate(@Param("reference") String reference);

    /** Ids of remittances in these statuses whose next attempt is due (or was never scheduled). */
    @Query("select r.id from Remittance r where r.status in :statuses and (r.nextAttemptAt is null or r.nextAttemptAt <= :now) "
            + "order by r.updatedAt")
    List<UUID> findDueIds(@Param("statuses") Collection<RemittanceStatus> statuses, @Param("now") Instant now, Pageable page);

    @Query("select r.id from Remittance r where r.status = :status order by r.updatedAt")
    List<UUID> findIdsByStatus(@Param("status") RemittanceStatus status, Pageable page);

    @Query("select r.reference from Remittance r where r.status = :status and r.createdAt < :before order by r.createdAt")
    List<String> findReferencesByStatusCreatedBefore(@Param("status") RemittanceStatus status, @Param("before") Instant before, Pageable page);

    /** Claims the idempotency key for this client: 1 if this call owns it, 0 if the key was already used. */
    @Modifying
    @Query(value = """
            insert into remittance_claim (client_name, idempotency_key, request_hash, remittance_id, created_at)
            values (:client, :key, :hash, :id, now())
            on conflict (client_name, idempotency_key) do nothing
            """, nativeQuery = true)
    int claim(@Param("client") String client, @Param("key") String key, @Param("hash") String hash, @Param("id") UUID id);

    @Query(value = "select remittance_id from remittance_claim where client_name = :client and idempotency_key = :key", nativeQuery = true)
    Optional<UUID> claimedRemittance(@Param("client") String client, @Param("key") String key);

    @Query(value = "select request_hash from remittance_claim where client_name = :client and idempotency_key = :key", nativeQuery = true)
    Optional<String> claimedHash(@Param("client") String client, @Param("key") String key);
}
