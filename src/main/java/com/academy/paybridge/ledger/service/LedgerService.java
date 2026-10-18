package com.academy.paybridge.ledger.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.ledger.api.AccountLineView;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerLineView;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.ledger.domain.JournalEntry;
import com.academy.paybridge.ledger.domain.LedgerLine;
import com.academy.paybridge.ledger.repository.JournalEntryRepository;
import com.academy.paybridge.ledger.repository.LedgerLineRepository;
import com.academy.paybridge.shared.exception.ConflictException;
import com.academy.paybridge.shared.exception.CurrencyMismatchException;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class LedgerService implements LedgerApi {

    private final JournalEntryRepository journalEntryRepository;
    private final LedgerLineRepository ledgerLineRepository;
    private final AccountApi accountApi;

    public LedgerService(JournalEntryRepository journalEntryRepository,
                         LedgerLineRepository ledgerLineRepository,
                         AccountApi accountApi) {
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerLineRepository = ledgerLineRepository;
        this.accountApi = accountApi;
    }

    @Override
    public PostedEntry post(PostingRequest request) {
        return doPost(request, null);
    }

    @Override
    public PostedEntry reverse(UUID entryId, String reference, String description) {
        JournalEntry original = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry", entryId));

        List<PostingLine> undoing = ledgerLineRepository.findByEntryId(entryId).stream()
                .map(line -> new PostingLine(
                        line.getAccountId(),
                        Money.ofMinor(line.getAmountMinor(), Currency.getInstance(line.getCurrency())).negate()))
                .toList();

        return doPost(new PostingRequest(EntryType.REVERSAL, reference, description, undoing), original.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PostedEntry> find(EntryType type, String reference) {
        return journalEntryRepository.findByEntryTypeAndReference(type, reference).map(this::toPosted);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerLineView> statement(UUID accountId, int limit) {
        return ledgerLineRepository.statement(accountId, PageRequest.of(0, limit)).stream()
                .map(row -> new LedgerLineView(row.entryId(), row.entryType(), row.reference(),
                        row.description(), row.amountMinor(), row.currency(), row.createdAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountLineView> linesBefore(UUID accountId, Instant before, int limit) {
        return ledgerLineRepository.linesBefore(accountId, before, PageRequest.of(0, Math.max(1, limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public long sumOfLines(UUID accountId) {
        return ledgerLineRepository.sumByAccountId(accountId);
    }

    private PostedEntry doPost(PostingRequest request, UUID reversesEntryId) {
        Currency currency = validateShape(request);

        // 1. Lock every account involved (always in id order, so opposite-direction postings cannot deadlock).
        Set<UUID> accountIds = new LinkedHashSet<>();
        request.lines().forEach(line -> accountIds.add(line.accountId()));
        Map<UUID, AccountSummary> accounts = new HashMap<>();
        accountApi.lockAccounts(accountIds).forEach(account -> accounts.put(account.id(), account));

        // 2. Retry safety: the same event posted twice must not move money twice.
        Optional<JournalEntry> existing =
                journalEntryRepository.findByEntryTypeAndReference(request.type(), request.reference());
        if (existing.isPresent()) {
            return replayOf(existing.get(), request);
        }
        if (reversesEntryId != null && journalEntryRepository.existsByReversesEntryId(reversesEntryId)) {
            throw new ConflictException("Journal entry " + reversesEntryId + " has already been reversed");
        }

        // 3. Check everything BEFORE changing anything, so a failure can never leave a half-applied entry behind.
        Map<UUID, Long> netByAccount = netByAccount(request.lines());
        for (Map.Entry<UUID, Long> change : netByAccount.entrySet()) {
            AccountSummary account = accounts.get(change.getKey());
            if (!account.currency().equals(currency)) {
                throw new CurrencyMismatchException(account.currency(), currency);
            }
            if (!account.isActive()) {
                throw new PayBridgeException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                        "Account " + describe(account) + " is not active");
            }
            if (account.isCustomerAccount() && Math.addExact(account.balanceMinor(), change.getValue()) < 0) {
                throw new InsufficientFundsException("Insufficient funds in account " + describe(account));
            }
        }

        // 4. Apply to the balances and write the permanent record.
        netByAccount.forEach(accountApi::applyBalanceChange);

        JournalEntry entry = journalEntryRepository.save(new JournalEntry(
                request.type(), request.reference(), request.description(), reversesEntryId));
        for (PostingLine line : request.lines()) {
            ledgerLineRepository.save(new LedgerLine(
                    entry.getId(), line.accountId(), line.amount().minorUnits(),
                    line.amount().currency().getCurrencyCode()));
        }
        return toPosted(entry);
    }

    /** Checks the request is well formed and balanced. Returns the (single) currency of the entry. */
    private static Currency validateShape(PostingRequest request) {
        if (request == null || request.type() == null) {
            throw invalid("A journal entry needs a type");
        }
        if (request.reference() == null || request.reference().isBlank() || request.reference().length() > 100) {
            throw invalid("A journal entry needs a reference of 1 to 100 characters");
        }
        List<PostingLine> lines = request.lines();
        if (lines == null || lines.size() < 2) {
            throw invalid("A journal entry needs at least two lines");
        }

        Currency currency = null;
        long total = 0;
        for (PostingLine line : lines) {
            if (line == null || line.accountId() == null || line.amount() == null) {
                throw invalid("Every line needs an account and an amount");
            }
            if (line.amount().isZero()) {
                throw invalid("A line may not have a zero amount");
            }
            if (currency == null) {
                currency = line.amount().currency();
            } else if (!currency.equals(line.amount().currency())) {
                throw new CurrencyMismatchException(currency, line.amount().currency());
            }
            total = Math.addExact(total, line.amount().minorUnits());
        }
        if (total != 0) {
            throw invalid("The journal entry does not balance: the lines add up to " + total + " instead of 0");
        }
        return currency;
    }

    private static Map<UUID, Long> netByAccount(List<PostingLine> lines) {
        Map<UUID, Long> net = new java.util.LinkedHashMap<>();
        for (PostingLine line : lines) {
            net.merge(line.accountId(), line.amount().minorUnits(), Math::addExact);
        }
        return net;
    }

    private PostedEntry replayOf(JournalEntry existing, PostingRequest request) {
        Map<UUID, Long> stored = new HashMap<>();
        ledgerLineRepository.findByEntryId(existing.getId())
                .forEach(line -> stored.merge(line.getAccountId(), line.getAmountMinor(), Math::addExact));

        if (!stored.equals(netByAccount(request.lines()))) {
            throw new ConflictException("Reference " + request.reference() + " was already used for a "
                    + request.type() + " entry with different lines");
        }
        return toPosted(existing);
    }

    private PostedEntry toPosted(JournalEntry entry) {
        List<PostingLine> lines = new ArrayList<>();
        for (LedgerLine line : ledgerLineRepository.findByEntryId(entry.getId())) {
            lines.add(new PostingLine(
                    line.getAccountId(),
                    Money.ofMinor(line.getAmountMinor(), Currency.getInstance(line.getCurrency()))));
        }
        return new PostedEntry(entry.getId(), entry.getEntryType(), entry.getReference(),
                entry.getDescription(), entry.getReversesEntryId(), entry.getCreatedAt(), lines);
    }

    private static String describe(AccountSummary account) {
        return account.accountNumber() != null ? account.accountNumber() : account.code();
    }

    private static PayBridgeException invalid(String message) {
        return new PayBridgeException(ErrorCode.VALIDATION_FAILED, message);
    }
}
