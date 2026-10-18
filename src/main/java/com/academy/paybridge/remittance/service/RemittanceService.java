package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.Quote;
import com.academy.paybridge.remittance.api.CreateRemittance;
import com.academy.paybridge.remittance.api.RecipientType;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Starts remittances and answers questions about them. The money-moving steps live elsewhere ({@link FundingService},
 * {@link RemittanceStepper}); this class only accepts the request, safely and exactly once.
 */
@Service
@Transactional
public class RemittanceService implements RemittanceApi {

    private static final Pattern PARTNER_CODE = Pattern.compile("[a-z0-9_-]{2,16}");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{10}");
    private static final Pattern BANK_CODE = Pattern.compile("\\d{3,6}");

    private final RemittanceRepository repository;
    private final FxApi fx;
    private final AccountApi accounts;
    private final ScreeningGate gate;
    private final RemittanceAudit audit;
    private final RemittanceProperties properties;
    private final Clock clock;

    RemittanceService(RemittanceRepository repository, FxApi fx, AccountApi accounts, ScreeningGate gate, RemittanceAudit audit,
                      RemittanceProperties properties, Clock clock) {
        this.repository = repository;
        this.fx = fx;
        this.accounts = accounts;
        this.gate = gate;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Created create(CreateRemittance command, String clientName, String idempotencyKey) {
        validate(command);
        String partner = command.partnerCode().toLowerCase(Locale.ROOT);
        String hash = hash(command, partner);
        UUID id = UUID.randomUUID();

        // the first request with this (client, key) owns it; a repeat gets the original back instead of a second remittance
        if (repository.claim(clientName, idempotencyKey, hash, id) == 0) {
            String earlierHash = repository.claimedHash(clientName, idempotencyKey).orElseThrow();
            if (!earlierHash.equals(hash)) {
                throw new PayBridgeException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different remittance request");
            }
            UUID earlier = repository.claimedRemittance(clientName, idempotencyKey).orElseThrow();
            return new Created(RemittanceMapper.view(find(earlier)), false);
        }

        if (repository.existsByPartnerCodeAndPartnerReference(partner, command.partnerReference())) {
            throw new PayBridgeException(ErrorCode.CONFLICT, "The partner reference " + command.partnerReference() + " was already used");
        }

        // uses the quote up: refused (and nothing saved) if it is missing, expired or already used
        Quote quote = fx.consumeQuote(command.quoteId(), id);
        requireInSystemRecipientIsUsable(command.recipient());

        String reference = "RM-" + id.toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        String senderKey = partner + ":" + command.sender().reference();
        String recipientKey = (command.recipient().type() == RecipientType.PAYBRIDGE ? "PB" : command.recipient().bankCode())
                + ":" + command.recipient().accountNumber();
        Remittance remittance = repository.saveAndFlush(Remittance.create(new Remittance.Facts(id, reference, clientName,
                idempotencyKey, hash, partner, command.partnerReference(), quote.corridor(), quote.id(), quote.sellMinor(),
                quote.feeMinor(), quote.buyMinor(), senderKey, command.sender().fullName().trim(),
                upper(command.sender().country()), command.sender().dateOfBirth(), recipientKey,
                command.recipient().fullName().trim(), upper(command.recipient().country()), command.recipient().type(),
                command.recipient().type() == RecipientType.BANK ? command.recipient().bankCode() : null,
                command.recipient().accountNumber(), clock.instant().plus(properties.fundingWindow()))));
        audit.recordCreated(remittance);

        gate.run(remittance);
        return new Created(RemittanceMapper.view(remittance), true);
    }

    @Override
    @Transactional(readOnly = true)
    public RemittanceView get(UUID remittanceId) {
        return RemittanceMapper.view(find(remittanceId));
    }

    @Override
    @Transactional(readOnly = true)
    public RemittanceView getByReference(String reference) {
        return RemittanceMapper.view(repository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Remittance", reference)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RemittanceView> listForSender(String partnerCode, String senderReference, int limit) {
        String senderKey = partnerCode.toLowerCase(Locale.ROOT) + ":" + senderReference;
        return repository.findBySenderKeyOrderByCreatedAtDesc(senderKey, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
                .stream().map(RemittanceMapper::view).toList();
    }

    private Remittance find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Remittance", id));
    }

    private void validate(CreateRemittance c) {
        if (c == null || c.sender() == null || c.recipient() == null || c.quoteId() == null) {
            throw invalid("A remittance needs a quote, a sender and a recipient");
        }
        if (c.partnerCode() == null || !PARTNER_CODE.matcher(c.partnerCode().toLowerCase(Locale.ROOT)).matches()
                || !properties.partnerSet().contains(c.partnerCode().toLowerCase(Locale.ROOT))) {
            throw invalid("Unknown corridor partner");
        }
        if (c.partnerReference() == null || c.partnerReference().isBlank() || c.partnerReference().length() > 100) {
            throw invalid("The partner reference is required (up to 100 characters)");
        }
        CreateRemittance.Sender s = c.sender();
        if (s.reference() == null || s.reference().isBlank() || s.reference().length() > 80 || s.fullName() == null
                || s.fullName().isBlank() || s.fullName().length() > 200) {
            throw invalid("The sender needs a reference (up to 80 characters) and a name (up to 200)");
        }
        CreateRemittance.Recipient r = c.recipient();
        if (r.type() == null || r.fullName() == null || r.fullName().isBlank() || r.fullName().length() > 100) {
            throw invalid("The recipient needs a type and a name (up to 100 characters)");
        }
        if (r.accountNumber() == null || !ACCOUNT_NUMBER.matcher(r.accountNumber()).matches()) {
            throw invalid("The recipient account number must be 10 digits");
        }
        if (r.type() == RecipientType.BANK && (r.bankCode() == null || !BANK_CODE.matcher(r.bankCode()).matches())) {
            throw invalid("A bank recipient needs a bank code of 3 to 6 digits");
        }
        for (String country : new String[]{s.country(), r.country()}) {
            if (country != null && !country.matches("[A-Za-z]{2}")) {
                throw invalid("A country must be a two-letter code");
            }
        }
    }

    /** A recipient at PayBridge must really exist and be able to receive naira, or we would take the money and then fail to pay. */
    private void requireInSystemRecipientIsUsable(CreateRemittance.Recipient recipient) {
        if (recipient.type() != RecipientType.PAYBRIDGE) {
            return;
        }
        AccountSummary account;
        try {
            account = accounts.getByAccountNumber(recipient.accountNumber());
        } catch (ResourceNotFoundException e) {
            throw invalid("There is no PayBridge account with that number");
        }
        if (!account.isCustomerAccount() || !"NGN".equals(account.currency().getCurrencyCode()) || !account.isActive()) {
            throw invalid("That PayBridge account cannot receive naira");
        }
    }

    private static PayBridgeException invalid(String message) {
        return new PayBridgeException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static String upper(String country) {
        return country == null ? null : country.toUpperCase(Locale.ROOT);
    }

    private static String hash(CreateRemittance c, String partner) {
        String canonical = String.join("|", partner, c.partnerReference(), c.quoteId().toString(), c.sender().reference(),
                c.sender().fullName().trim(), String.valueOf(upper(c.sender().country())), String.valueOf(c.sender().dateOfBirth()),
                c.recipient().type().name(), c.recipient().fullName().trim(), String.valueOf(upper(c.recipient().country())),
                String.valueOf(c.recipient().bankCode()), c.recipient().accountNumber());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
