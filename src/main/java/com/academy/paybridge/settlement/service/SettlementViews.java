package com.academy.paybridge.settlement.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** What the settlement endpoints return. Every figure is a whole number of minor units. */
public final class SettlementViews {

    private SettlementViews() {
    }

    /**
     * A frozen slice of the ledger movements on a partner's settlement account.
     *
     * @param openingMinor   the account's total at the end of the previous batch (what the earlier batches add up to)
     * @param movementsMinor the net of the lines in this batch; negative means the partner holds money for us
     * @param closingMinor   opening plus movements
     */
    public record BatchView(UUID id, String partnerCode, int sequence, String currency, String status, Instant cutoffAt,
                            long openingMinor, long movementsMinor, long closingMinor, int lineCount, UUID paymentEntryId,
                            String disputeNote, Instant closedAt, Instant instructedAt, Instant confirmedAt, Instant reconciledAt) {
    }

    public record BatchLineView(UUID ledgerLineId, UUID entryId, String entryType, String reference, long amountMinor, String currency,
                                Instant postedAt) {
    }

    public record BreakView(UUID id, UUID batchId, String type, UUID batchLineId, UUID statementLineId, Long ourAmountMinor,
                            Long theirAmountMinor, String reason, String status, String owner, String resolutionNote,
                            String resolvedBy, Instant resolvedAt, UUID adjustmentEntryId, Instant createdAt) {
    }

    /** The result of loading a statement. {@code duplicate} is true when the very same file had already been loaded. */
    public record StatementResult(UUID statementId, boolean duplicate, int lineCount, int matched, int amountDiffers,
                                  int unmatchedOurs, int unmatchedTheirs, int breaksOpen) {
    }

    /** What a partner's account holds right now, split into what is in a batch and what is still waiting for the next one. */
    public record Position(String partnerCode, String currency, long accountBalanceMinor, long batchedMinor, long unbatchedMinor,
                           int unbatchedLines) {
    }

    /**
     * A batch in full: the figures, how the statement compared, and the breaks by age.
     *
     * @param netToSettleMinor what the batch obliges the sides to pay (its lines without earlier settlement payments)
     * @param tracesToLedger true when the closing figure equals the sum of every ledger line on the account before the cut-off
     *                       (false means a posting was still being committed at the cut-off; the next batch will carry it)
     */
    public record BatchReport(UUID batchId, String partnerCode, int sequence, String status, String currency, LocalDate cutoffDate,
                              long openingMinor, long movementsMinor, long closingMinor, long netToSettleMinor, int lineCount,
                              int statementLines, int matchedLines, int openBreaks, int resolvedBreaks, Map<String, Integer> openBreaksByAge,
                              boolean tracesToLedger) {
    }
}
