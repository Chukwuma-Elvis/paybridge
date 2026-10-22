package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    Optional<Account> findByCode(String code);

    /**
     * Just the id (and currency) of a system account, as plain values. Looking an account up as a whole entity would leave a copy in
     * the session, and a later row lock would then find a newer committed version and fail, so id lookups avoid entities.
     */
    @Query("select a.id from Account a where a.code = :code")
    Optional<UUID> findIdByCode(@Param("code") String code);

    @Query("select a.currency from Account a where a.code = :code")
    Optional<String> findCurrencyByCode(@Param("code") String code);

    List<Account> findByOwnerCustomerIdOrderByCreatedAtAsc(UUID ownerCustomerId);

    List<Account> findByTypeOrderByCreatedAtDesc(AccountType type);

    /** SELECT ... FOR UPDATE: other transactions wait here until we commit or roll back. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountSerial();

    /** Creates one of the bank's own accounts unless an account with this code already exists. Returns 1 if created, 0 if it was there. */
    @Modifying
    @Query(value = """
            insert into account (id, code, account_type, currency, balance_minor, status, version, created_at, updated_at)
            values (gen_random_uuid(), :code, 'SYSTEM', :currency, 0, 'ACTIVE', 0, now(), now())
            on conflict (code) do nothing
            """, nativeQuery = true)
    int createSystemAccountIfAbsent(@Param("code") String code, @Param("currency") String currency);
}
