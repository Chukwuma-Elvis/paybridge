package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.domain.FxRate;
import com.academy.paybridge.fx.domain.RateStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FxRateRepository extends JpaRepository<FxRate, UUID> {

    /** The rate most recently put into force (or held) for a corridor. */
    Optional<FxRate> findFirstByCorridorAndStatusOrderByCreatedAtDescIdDesc(Corridor corridor, RateStatus status);

    /** The approval row for a held rate, if it has been approved. */
    Optional<FxRate> findByApprovesRateId(UUID heldRateId);

    List<FxRate> findByCorridorOrderByCreatedAtDescIdDesc(Corridor corridor, Pageable page);

    /**
     * Adds the approval row for a held rate unless one already exists. Returns 1 if this call added it, 0 if it was there. If two
     * admins approve the same rate at the same instant, one waits for the other and gets 0, so there is exactly one approval.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            insert into fx_rate (id, corridor, mid_rate, source, retrieved_at, status, approves_rate_id, note, created_at)
            values (gen_random_uuid(), :corridor, :midRate, :source, :retrievedAt, 'ACTIVE', :approves, :note, now())
            on conflict (approves_rate_id) do nothing
            """, nativeQuery = true)
    int approveIfAbsent(@Param("corridor") String corridor,
                        @Param("midRate") BigDecimal midRate,
                        @Param("source") String source,
                        @Param("retrievedAt") Instant retrievedAt,
                        @Param("approves") UUID approves,
                        @Param("note") String note);
}
