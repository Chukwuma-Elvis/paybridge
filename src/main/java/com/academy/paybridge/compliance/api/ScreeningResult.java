package com.academy.paybridge.compliance.api;

import java.util.UUID;

/**
 * @param caseId  the case opened for a REVIEW or BLOCK; null when CLEAR
 * @param summary one line for the record, never the screened party's details
 */
public record ScreeningResult(ScreeningOutcome outcome, UUID caseId, String summary) {

    public boolean isClear() {
        return outcome == ScreeningOutcome.CLEAR;
    }
}
