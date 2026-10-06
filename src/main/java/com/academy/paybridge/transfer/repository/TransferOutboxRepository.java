package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.TransferOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferOutboxRepository extends JpaRepository<TransferOutbox, UUID> {

    Optional<TransferOutbox> findByTransferId(UUID transferId);

    @Query("""
            select o.transferId from TransferOutbox o
            where o.status = :status and o.nextAttemptAt <= :now
            order by o.nextAttemptAt
            """)
    List<UUID> findDueTransferIds(@Param("status") TransferOutbox.Status status,
                                  @Param("now") Instant now,
                                  Pageable pageable);
}
