package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /**
     * Claims the key for this request. Returns 1 if we claimed it, 0 if somebody already had.
     * If another transaction is claiming the same key right now, this waits for it to finish first,
     * so exactly one of two simultaneous identical requests ever gets 1.
     */
    @Modifying
    @Query(value = """
            insert into idempotency_key (id, scope, idem_key, request_hash, created_at)
            values (gen_random_uuid(), :scope, :key, :hash, now())
            on conflict (scope, idem_key) do nothing
            """, nativeQuery = true)
    int claim(@Param("scope") String scope, @Param("key") String key, @Param("hash") String hash);

    @Query("select k from IdempotencyKey k where k.scope = :scope and k.idemKey = :key")
    Optional<IdempotencyKey> find(@Param("scope") String scope, @Param("key") String key);
}
