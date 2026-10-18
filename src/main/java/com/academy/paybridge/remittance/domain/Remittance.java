package com.academy.paybridge.remittance.domain;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.remittance.api.RecipientType;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.shared.audit.AuditableEntity;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One cross-border payment. The status changes ONLY through {@link #moveTo}, which refuses any move the state machine does not allow,
 * so no code anywhere can put a remittance into an impossible state.
 */
@Entity
@Table(name = "remittance")
public class Remittance extends AuditableEntity {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(name = "client_name", nullable = false, updatable = false)
    private String clientName;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "partner_code", nullable = false, updatable = false)
    private String partnerCode;

    @Column(name = "partner_reference", nullable = false, updatable = false)
    private String partnerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Corridor corridor;

    @Column(name = "quote_id", nullable = false, updatable = false)
    private UUID quoteId;

    @Column(name = "sell_minor", nullable = false, updatable = false)
    private long sellMinor;

    @Column(name = "fee_minor", nullable = false, updatable = false)
    private long feeMinor;

    @Column(name = "buy_minor", nullable = false, updatable = false)
    private long buyMinor;

    @Column(name = "sender_key", nullable = false, updatable = false)
    private String senderKey;

    @Column(name = "sender_name", nullable = false, updatable = false)
    private String senderName;

    @Column(name = "sender_country", updatable = false)
    private String senderCountry;

    @Column(name = "sender_birth", updatable = false)
    private LocalDate senderBirth;

    @Column(name = "recipient_key", nullable = false, updatable = false)
    private String recipientKey;

    @Column(name = "recipient_name", nullable = false, updatable = false)
    private String recipientName;

    @Column(name = "recipient_country", updatable = false)
    private String recipientCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, updatable = false)
    private RecipientType recipientType;

    @Column(name = "recipient_bank_code", updatable = false)
    private String recipientBankCode;

    @Column(name = "recipient_account_number", nullable = false, updatable = false)
    private String recipientAccountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RemittanceStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "sender_case_id")
    private UUID senderCaseId;

    @Column(name = "recipient_case_id")
    private UUID recipientCaseId;

    @Column(name = "velocity_case_id")
    private UUID velocityCaseId;

    @Column(name = "funding_case_id")
    private UUID fundingCaseId;

    @Column(name = "usd_usage_date")
    private LocalDate usdUsageDate;

    @Column(name = "limits_reserved", nullable = false)
    private boolean limitsReserved;

    @Column(name = "funding_expires_at", nullable = false)
    private Instant fundingExpiresAt;

    @Column(name = "funded_at")
    private Instant fundedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payout_reference")
    private String payoutReference;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Version
    private long version;

    protected Remittance() {
    }

    /** Everything that never changes after creation. */
    public record Facts(UUID id, String reference, String clientName, String idempotencyKey, String requestHash, String partnerCode,
                        String partnerReference, Corridor corridor, UUID quoteId, long sellMinor, long feeMinor, long buyMinor,
                        String senderKey, String senderName, String senderCountry, LocalDate senderBirth, String recipientKey,
                        String recipientName, String recipientCountry, RecipientType recipientType, String recipientBankCode,
                        String recipientAccountNumber, Instant fundingExpiresAt) {
    }

    public static Remittance create(Facts f) {
        Remittance r = new Remittance();
        r.id = f.id();
        r.reference = f.reference();
        r.clientName = f.clientName();
        r.idempotencyKey = f.idempotencyKey();
        r.requestHash = f.requestHash();
        r.partnerCode = f.partnerCode();
        r.partnerReference = f.partnerReference();
        r.corridor = f.corridor();
        r.quoteId = f.quoteId();
        r.sellMinor = f.sellMinor();
        r.feeMinor = f.feeMinor();
        r.buyMinor = f.buyMinor();
        r.senderKey = f.senderKey();
        r.senderName = f.senderName();
        r.senderCountry = f.senderCountry();
        r.senderBirth = f.senderBirth();
        r.recipientKey = f.recipientKey();
        r.recipientName = f.recipientName();
        r.recipientCountry = f.recipientCountry();
        r.recipientType = f.recipientType();
        r.recipientBankCode = f.recipientBankCode();
        r.recipientAccountNumber = f.recipientAccountNumber();
        r.fundingExpiresAt = f.fundingExpiresAt();
        r.status = RemittanceStatus.CREATED;
        return r;
    }

    /** The one and only way the status changes. */
    public void moveTo(RemittanceStatus next, String reason) {
        if (!status.canMoveTo(next)) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE,
                    "Remittance " + reference + " cannot move from " + status + " to " + next);
        }
        this.status = next;
        this.statusReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 255));
    }

    /** Records when the money arrived and clears the retry bookkeeping. The status itself moves through {@link #moveTo}. */
    public void markFunded(Instant at) {
        this.fundedAt = at;
        this.attempts = 0;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void markPaid(Instant at) {
        this.paidAt = at;
        this.nextAttemptAt = null;
    }

    /** A step worked: clear the retry bookkeeping so the next step is due straight away. */
    public void stepSucceeded() {
        this.attempts = 0;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void stepFailed(String error, Instant retryAt) {
        this.attempts++;
        this.nextAttemptAt = retryAt;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 255));
    }

    public void waitUntil(Instant at) {
        this.nextAttemptAt = at;
    }

    public void limitsReserved(LocalDate usageDate) {
        this.limitsReserved = true;
        this.usdUsageDate = usageDate;
    }

    public void limitsReleased() {
        this.limitsReserved = false;
    }

    public void recordCases(UUID sender, UUID recipient, UUID velocity) {
        this.senderCaseId = sender;
        this.recipientCaseId = recipient;
        this.velocityCaseId = velocity;
    }

    public void recordFundingCase(UUID caseId) {
        this.fundingCaseId = caseId;
    }

    public void recordPayoutReference(String payoutReference) {
        this.payoutReference = payoutReference;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getClientName() {
        return clientName;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getPartnerCode() {
        return partnerCode;
    }

    public String getPartnerReference() {
        return partnerReference;
    }

    public Corridor getCorridor() {
        return corridor;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public long getSellMinor() {
        return sellMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public long getBuyMinor() {
        return buyMinor;
    }

    public String getSenderKey() {
        return senderKey;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getSenderCountry() {
        return senderCountry;
    }

    public LocalDate getSenderBirth() {
        return senderBirth;
    }

    public String getRecipientKey() {
        return recipientKey;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getRecipientCountry() {
        return recipientCountry;
    }

    public RecipientType getRecipientType() {
        return recipientType;
    }

    public String getRecipientBankCode() {
        return recipientBankCode;
    }

    public String getRecipientAccountNumber() {
        return recipientAccountNumber;
    }

    public RemittanceStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public UUID getSenderCaseId() {
        return senderCaseId;
    }

    public UUID getRecipientCaseId() {
        return recipientCaseId;
    }

    public UUID getVelocityCaseId() {
        return velocityCaseId;
    }

    public UUID getFundingCaseId() {
        return fundingCaseId;
    }

    public LocalDate getUsdUsageDate() {
        return usdUsageDate;
    }

    public boolean isLimitsReserved() {
        return limitsReserved;
    }

    public Instant getFundingExpiresAt() {
        return fundingExpiresAt;
    }

    public Instant getFundedAt() {
        return fundedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getPayoutReference() {
        return payoutReference;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
