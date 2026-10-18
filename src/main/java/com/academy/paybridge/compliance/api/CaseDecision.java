package com.academy.paybridge.compliance.api;

public enum CaseDecision {
    APPROVE(CaseStatus.APPROVED), REJECT(CaseStatus.REJECTED);

    private final CaseStatus resultingStatus;

    CaseDecision(CaseStatus resultingStatus) {
        this.resultingStatus = resultingStatus;
    }

    public CaseStatus resultingStatus() {
        return resultingStatus;
    }
}
