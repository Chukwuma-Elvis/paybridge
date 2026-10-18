package com.academy.paybridge.fx.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.fx.api.Conversion;
import com.academy.paybridge.fx.api.QuoteStatus;
import com.academy.paybridge.fx.domain.FxQuote;
import com.academy.paybridge.fx.repository.FxQuoteRepository;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Carries out a consumed quote in the ledger. A conversion is TWO entries, because every ledger entry has one currency and sums to
 * zero on its own:
 * <pre>
 *   USD leg   clearing USD  -sell      FX position USD  +(sell - fee)     fees USD  +fee
 *   NGN leg   FX position NGN -buy      clearing NGN     +buy
 * </pre>
 * The two FX position accounts hold the house's open exposure (what it is long or short in each currency). Both legs are posted
 * in one transaction, so it is both or neither.
 */
@Service
@Transactional
public class ConversionService {

    static final String FEES_USD = "FEES_USD";
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency NGN = Currency.getInstance("NGN");

    private static final RowMapper<Conversion> ROW = (rs, n) -> new Conversion(
            rs.getObject("id", UUID.class), rs.getObject("quote_id", UUID.class),
            rs.getLong("sell_minor"), rs.getLong("fee_minor"), rs.getLong("buy_minor"),
            rs.getObject("usd_entry_id", UUID.class), rs.getObject("ngn_entry_id", UUID.class),
            "REVERSED".equals(rs.getString("status")), rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final FxQuoteRepository quotes;
    private final LedgerApi ledger;
    private final AccountApi accounts;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    ConversionService(FxQuoteRepository quotes, LedgerApi ledger, AccountApi accounts, JdbcTemplate jdbc, MeterRegistry meters) {
        this.quotes = quotes;
        this.ledger = ledger;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /** Posts both legs for a consumed quote. Asking again for the same quote returns the same conversion and moves nothing. */
    public Conversion convert(UUID quoteId, UUID usdSourceAccountId, UUID ngnTargetAccountId) {
        FxQuote quote = quotes.findById(quoteId)
                .orElseThrow(() -> new ResourceNotFoundException("Quote", quoteId));
        if (quote.getStatus() != QuoteStatus.CONSUMED) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE,
                    "Quote " + quote.getReference() + " must be used by a remittance before it can be converted");
        }
        Optional<Conversion> existing = find(quoteId);
        if (existing.isPresent()) {
            return existing.get();
        }

        long sell = quote.getSellMinor();
        long fee = quote.getFeeMinor();
        long buy = quote.getBuyMinor();
        String reference = "CONV-" + quote.getReference();

        UUID positionUsd = accounts.systemAccountId(SystemAccount.FX_POSITION_USD);
        UUID positionNgn = accounts.systemAccountId(SystemAccount.FX_POSITION_NGN);
        UUID feesUsd = fee > 0 ? accounts.ensureSystemAccount(FEES_USD, "USD") : null;
        // lock every account involved up front, in one consistent order (the account module sorts them), and check the currencies
        // from the locked copies: both legs then run with nothing else able to touch these accounts
        List<UUID> involved = new ArrayList<>(List.of(usdSourceAccountId, ngnTargetAccountId, positionUsd, positionNgn));
        if (feesUsd != null) {
            involved.add(feesUsd);
        }
        List<AccountSummary> locked = accounts.lockAccounts(involved);
        requireCurrency(locked, usdSourceAccountId, USD, "The dollar clearing account");
        requireCurrency(locked, ngnTargetAccountId, NGN, "The naira clearing account");

        List<PostingLine> usdLines = new ArrayList<>();
        usdLines.add(new PostingLine(usdSourceAccountId, Money.ofMinor(-sell, USD)));
        usdLines.add(new PostingLine(positionUsd, Money.ofMinor(sell - fee, USD)));
        if (fee > 0) {
            usdLines.add(new PostingLine(feesUsd, Money.ofMinor(fee, USD)));
        }
        PostedEntry usdEntry = ledger.post(new PostingRequest(EntryType.FX_CONVERSION_USD, reference,
                "Conversion of " + quote.getCorridor() + " (dollar leg)", usdLines));

        PostedEntry ngnEntry = ledger.post(PostingRequest.of(EntryType.FX_CONVERSION_NGN, reference,
                "Conversion of " + quote.getCorridor() + " (naira leg)",
                new PostingLine(positionNgn, Money.ofMinor(-buy, NGN)),
                new PostingLine(ngnTargetAccountId, Money.ofMinor(buy, NGN))));

        jdbc.update("""
                insert into fx_conversion (id, quote_id, sell_minor, fee_minor, buy_minor, usd_source_id, ngn_target_id,
                                           usd_entry_id, ngn_entry_id, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', now())
                on conflict (quote_id) do nothing
                """, UUID.randomUUID(), quoteId, sell, fee, buy, usdSourceAccountId, ngnTargetAccountId, usdEntry.id(), ngnEntry.id());
        meters.counter("paybridge.fx.conversions", "result", "posted").increment();
        return find(quoteId).orElseThrow();
    }

    /** Undoes both legs together (each as a reversing entry, never an edit). Repeating it changes nothing. */
    public Conversion reverse(UUID quoteId, String reason) {
        Conversion conversion = find(quoteId).orElseThrow(() -> new ResourceNotFoundException("Conversion for quote", quoteId));
        if (conversion.reversed()) {
            return conversion;
        }
        String why = reason == null || reason.isBlank() ? "Conversion reversed" : reason;
        String suffix = quoteId.toString();
        ledger.reverse(conversion.usdEntryId(), "CONV-REV-USD-" + suffix, why);
        ledger.reverse(conversion.ngnEntryId(), "CONV-REV-NGN-" + suffix, why);
        jdbc.update("update fx_conversion set status = 'REVERSED', reversed_at = now() where quote_id = ? and status = 'POSTED'", quoteId);
        meters.counter("paybridge.fx.conversions", "result", "reversed").increment();
        return find(quoteId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Conversion> find(UUID quoteId) {
        return jdbc.query("select * from fx_conversion where quote_id = ?", ROW, quoteId).stream().findFirst();
    }

    private static void requireCurrency(List<AccountSummary> locked, UUID accountId, Currency expected, String what) {
        AccountSummary account = locked.stream().filter(a -> a.id().equals(accountId)).findFirst().orElseThrow();
        if (!account.currency().equals(expected)) {
            throw new PayBridgeException(ErrorCode.CURRENCY_MISMATCH, what + " must be in " + expected.getCurrencyCode());
        }
    }
}
