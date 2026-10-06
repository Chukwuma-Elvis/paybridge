package com.academy.paybridge.transfer.service;

import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.client.ResolvedAccount;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.Beneficiary;
import com.academy.paybridge.transfer.repository.BeneficiaryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Saved payees at other banks.
 * <p>
 * Deliberately NOT transactional as a whole: talking to the provider can be slow, and we must never hold a database
 * transaction open while waiting for someone else's server. Each repository call is its own short transaction.
 */
@Service
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaryRepository;
    private final CustomerApi customerApi;
    private final TransferGateway gateway;

    public BeneficiaryService(BeneficiaryRepository beneficiaryRepository, CustomerApi customerApi, TransferGateway gateway) {
        this.beneficiaryRepository = beneficiaryRepository;
        this.customerApi = customerApi;
        this.gateway = gateway;
    }

    /** Name enquiry: "whose account is this?" Nothing is saved. */
    public ResolvedAccount resolve(String bankCode, String accountNumber) {
        return gateway.resolveAccount(bankCode, accountNumber);
    }

    /** Saves a payee for the customer. Saving the same payee twice just returns the one already saved. */
    public BeneficiaryView create(UUID customerId, String bankCode, String accountNumber) {
        CustomerSummary customer = customerApi.getCustomer(customerId);
        if (!customer.isActive()) {
            throw new PayBridgeException(ErrorCode.ACCOUNT_NOT_ACTIVE, "The customer is not active");
        }

        Optional<Beneficiary> existing =
                beneficiaryRepository.findByCustomerIdAndBankCodeAndAccountNumber(customerId, bankCode, accountNumber);
        if (existing.isPresent()) {
            return BeneficiaryView.from(existing.get());
        }

        ResolvedAccount resolved = gateway.resolveAccount(bankCode, accountNumber);
        String recipientCode = gateway.createRecipient(bankCode, accountNumber, resolved.accountName());

        try {
            return BeneficiaryView.from(beneficiaryRepository.save(
                    new Beneficiary(customerId, bankCode, accountNumber, resolved.accountName(), recipientCode)));
        } catch (DataIntegrityViolationException e) {
            // a simultaneous identical request saved it first: use that one
            return BeneficiaryView.from(beneficiaryRepository
                    .findByCustomerIdAndBankCodeAndAccountNumber(customerId, bankCode, accountNumber)
                    .orElseThrow(() -> e));
        }
    }

    public List<BeneficiaryView> listForCustomer(UUID customerId) {
        return beneficiaryRepository.findByCustomerIdOrderByCreatedAtAsc(customerId).stream()
                .map(BeneficiaryView::from)
                .toList();
    }
}
