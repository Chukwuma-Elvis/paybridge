package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.audit.AuditableEntity;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One movement of money requested by a customer. Its {@link TransferStatus} can only change along the legal
 * paths; every other change is refused with {@code INVALID_STATE}.
 */
@Entity
@Table(name = "transfer")
public class Transfer extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false, updatable = false)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(updatable = false)
    private UUID destinationAccountId;

    @Column(nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private long feeMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false)
    private String narration;

    @Column
    private String failureReason;

    /** Only external transfers have a beneficiary. */
    @Column(updatable = false)
    private UUID beneficiaryId;

    /** The provider's own reference for this transfer, once it has accepted it. */
    @Column
    private String providerReference;

    /** The Lagos day this transfer was counted against, so the daily allowance can be given back if it fails. */
    @Column(nullable = false, updatable = false)
    private LocalDate usageDate;

    @Column
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Transfer() {
    }

    private Transfer(TransferType type, UUID customerId, UUID sourceAccountId, UUID destinationAccountId,
                     Money amount, Money fee, String narration, LocalDate usageDate) {
        this.reference = "pb-" + UUID.randomUUID();
        this.type = type;
        this.status = TransferStatus.PENDING;
        this.customerId = customerId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amountMinor = amount.minorUnits();
        this.feeMinor = fee.minorUnits();
        this.currency = amount.currency().getCurrencyCode();
        this.narration = narration;
        this.usageDate = usageDate;
    }

    public static Transfer internal(UUID customerId, UUID sourceAccountId, UUID destinationAccountId,
                                    Money amount, String narration, LocalDate usageDate) {
        return new Transfer(TransferType.INTERNAL, customerId, sourceAccountId, destinationAccountId,
                amount, Money.zero(amount.currency()), narration, usageDate);
    }

    public static Transfer external(UUID customerId, UUID sourceAccountId, UUID beneficiaryId,
                                    Money amount, Money fee, String narration, LocalDate usageDate) {
        Transfer transfer = new Transfer(TransferType.EXTERNAL, customerId, sourceAccountId, null,
                amount, fee, narration, usageDate);
        transfer.beneficiaryId = beneficiaryId;
        return transfer;
    }

    public void recordProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public void markProcessing() {
        moveTo(TransferStatus.PROCESSING);
    }

    public void markSuccess(Instant now) {
        moveTo(TransferStatus.SUCCESS);
        this.completedAt = now;
    }

    public void markFailed(String reason, Instant now) {
        moveTo(TransferStatus.FAILED);
        this.failureReason = truncate(reason);
        this.completedAt = now;
    }

    public void markReversed(String reason, Instant now) {
        moveTo(TransferStatus.REVERSED);
        this.failureReason = truncate(reason);
        this.completedAt = now;
    }

    private void moveTo(TransferStatus next) {
        if (!status.canMoveTo(next)) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE,
                    "Transfer " + reference + " cannot go from " + status + " to " + next);
        }
        this.status = next;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 255 ? text : text.substring(0, 255);
    }

    public Money amount() {
        return Money.ofMinor(amountMinor, java.util.Currency.getInstance(currency));
    }

    public Money fee() {
        return Money.ofMinor(feeMinor, java.util.Currency.getInstance(currency));
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public TransferType getType() {
        return type;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
    }

    public UUID getBeneficiaryId() {
        return beneficiaryId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getNarration() {
        return narration;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transfer that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
