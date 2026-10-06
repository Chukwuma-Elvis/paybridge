package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    Optional<Account> findByCode(String code);

    List<Account> findByOwnerCustomerIdOrderByCreatedAtAsc(UUID ownerCustomerId);

    /** SELECT ... FOR UPDATE: other transactions wait here until we commit or roll back. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountSerial();
}
