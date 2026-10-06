package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.reference = :reference")
    Optional<Transfer> findByReferenceForUpdate(@Param("reference") String reference);

    @Query("""
            select t from Transfer t
            where t.sourceAccountId = :accountId or t.destinationAccountId = :accountId
            order by t.createdAt desc
            """)
    List<Transfer> findForAccount(@Param("accountId") UUID accountId, Pageable pageable);

    /**
     * Transfers we have handed to the provider (or tried to) and are still waiting to hear the outcome of,
     * whose dispatching is finished and that have been quiet since {@code cutoff}.
     */
    @Query("""
            select t from Transfer t
            where t.status = :processing
              and t.updatedAt <= :cutoff
              and exists (select 1 from TransferOutbox o where o.transferId = t.id and o.status = :done)
            order by t.updatedAt
            """)
    List<Transfer> findAwaitingOutcome(@Param("processing") TransferStatus processing,
                                       @Param("done") TransferOutbox.Status done,
                                       @Param("cutoff") Instant cutoff,
                                       Pageable pageable);
}
