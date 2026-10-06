package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.exception.CurrencyMismatchException;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferCompleted;
import com.academy.paybridge.transfer.api.TransferSummary;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class TransferService implements TransferApi {

    private final TransferRepository transferRepository;
    private final IdempotencyService idempotencyService;
    private final AccountApi accountApi;
    private final CustomerApi customerApi;
    private final ComplianceApi complianceApi;
    private final LedgerApi ledgerApi;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TransferService(TransferRepository transferRepository,
                           IdempotencyService idempotencyService,
                           AccountApi accountApi,
                           CustomerApi customerApi,
                           ComplianceApi complianceApi,
                           LedgerApi ledgerApi,
                           ApplicationEventPublisher events,
                           Clock clock) {
        this.transferRepository = transferRepository;
        this.idempotencyService = idempotencyService;
        this.accountApi = accountApi;
        this.customerApi = customerApi;
        this.complianceApi = complianceApi;
        this.ledgerApi = ledgerApi;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Moves money between two PayBridge accounts, all in ONE database transaction: the idempotency claim,
     * the compliance allowance, the transfer record, the ledger entry and the event commit together or not at all.
     */
    public TransferResult transferInternal(InternalTransferCommand command, String idempotencyKey) {
        String scope = "transfer:" + command.sourceAccountId();
        String fingerprint = IdempotencyService.fingerprint(command.sourceAccountId(),
                command.destinationAccountNumber(), command.amountMinor(), command.narration());

        Optional<UUID> alreadyDone = idempotencyService.begin(scope, idempotencyKey, fingerprint);
        if (alreadyDone.isPresent()) {
            return new TransferResult(getTransfer(alreadyDone.get()), true);
        }

        AccountSummary source = accountApi.getAccount(command.sourceAccountId());
        requireUsableCustomerAccount(source, "Source");
        AccountSummary destination = accountApi.getByAccountNumber(command.destinationAccountNumber());
        requireUsableCustomerAccount(destination, "Destination");

        if (source.id().equals(destination.id())) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "You cannot transfer to the same account");
        }
        if (!source.currency().equals(destination.currency())) {
            throw new CurrencyMismatchException(source.currency(), destination.currency());
        }

        CustomerSummary owner = customerApi.getCustomer(source.ownerCustomerId());
        if (!owner.isActive()) {
            throw new PayBridgeException(ErrorCode.ACCOUNT_NOT_ACTIVE, "The sending customer is not active");
        }

        Money amount = Money.ofMinor(command.amountMinor(), source.currency());
        if (!amount.isPositive()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The amount must be greater than zero");
        }

        complianceApi.reserve(owner.id(), amount);

        Transfer transfer = transferRepository.save(Transfer.internal(
                owner.id(), source.id(), destination.id(), amount, command.narration(), complianceApi.today()));

        ledgerApi.post(PostingRequest.of(EntryType.TRANSFER, transfer.getReference(), command.narration(),
                new PostingLine(source.id(), amount.negate()),
                new PostingLine(destination.id(), amount)));

        transfer.markSuccess(clock.instant());
        idempotencyService.complete(scope, idempotencyKey, transfer.getId());

        events.publishEvent(new TransferCompleted(transfer.getId(), transfer.getReference(), transfer.getType(),
                owner.id(), source.id(), destination.id(), amount.minorUnits(), 0, source.currency().getCurrencyCode()));

        return new TransferResult(TransferMapper.toSummary(transfer), false);
    }

    @Override
    @Transactional(readOnly = true)
    public TransferSummary getTransfer(UUID transferId) {
        return TransferMapper.toSummary(transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferSummary> listForAccount(UUID accountId, int limit) {
        return transferRepository.findForAccount(accountId, PageRequest.of(0, limit)).stream()
                .map(TransferMapper::toSummary)
                .toList();
    }

    private static void requireUsableCustomerAccount(AccountSummary account, String role) {
        TransferRules.requireUsableCustomerAccount(account, role);
    }
}
