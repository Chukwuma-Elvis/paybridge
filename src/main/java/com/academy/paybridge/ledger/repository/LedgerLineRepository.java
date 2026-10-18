package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.domain.LedgerLine;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerLineRepository extends JpaRepository<LedgerLine, UUID> {

    List<LedgerLine> findByEntryId(UUID entryId);

    @Query("select coalesce(sum(l.amountMinor), 0) from LedgerLine l where l.accountId = :accountId")
    long sumByAccountId(@Param("accountId") UUID accountId);

    @Query("""
            select new com.academy.paybridge.ledger.api.AccountLineView(
                l.id, e.id, e.entryType, e.reference, l.amountMinor, l.currency, l.createdAt)
            from LedgerLine l join JournalEntry e on e.id = l.entryId
            where l.accountId = :accountId and l.createdAt < :before
            order by l.createdAt asc, l.id asc
            """)
    List<com.academy.paybridge.ledger.api.AccountLineView> linesBefore(@Param("accountId") UUID accountId,
                                                                       @Param("before") java.time.Instant before, Pageable pageable);

    @Query("""
            select new com.academy.paybridge.ledger.repository.StatementRow(
                e.id, e.entryType, e.reference, e.description, l.amountMinor, l.currency, l.createdAt)
            from LedgerLine l join JournalEntry e on e.id = l.entryId
            where l.accountId = :accountId
            order by l.createdAt desc, l.id desc
            """)
    List<StatementRow> statement(@Param("accountId") UUID accountId, Pageable pageable);
}
