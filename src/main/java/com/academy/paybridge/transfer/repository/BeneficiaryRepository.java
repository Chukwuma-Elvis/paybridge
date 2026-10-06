package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    Optional<Beneficiary> findByCustomerIdAndBankCodeAndAccountNumber(UUID customerId, String bankCode, String accountNumber);

    List<Beneficiary> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);
}
