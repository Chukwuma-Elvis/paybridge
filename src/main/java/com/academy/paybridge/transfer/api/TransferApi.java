package com.academy.paybridge.transfer.api;

import java.util.List;
import java.util.UUID;

/** The front desk of the transfer module. */
public interface TransferApi {

    /** @throws com.academy.paybridge.shared.exception.ResourceNotFoundException if there is no such transfer */
    TransferSummary getTransfer(UUID transferId);

    /** Transfers sent from or received into the account, newest first. */
    List<TransferSummary> listForAccount(UUID accountId, int limit);
}
