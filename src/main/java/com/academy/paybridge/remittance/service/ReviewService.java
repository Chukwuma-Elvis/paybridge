package com.academy.paybridge.remittance.service;

import com.academy.paybridge.compliance.api.CaseDecision;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** The reviewer's side of a remittance that is UNDER_REVIEW: decide its open cases, and carry on or stop once they are all decided. */
@Service
@Transactional
public class ReviewService {

    private final RemittanceRepository repository;
    private final ComplianceApi compliance;
    private final ScreeningGate gate;

    ReviewService(RemittanceRepository repository, ComplianceApi compliance, ScreeningGate gate) {
        this.repository = repository;
        this.compliance = compliance;
        this.gate = gate;
    }

    /**
     * Records the same decision, with a written reason, on every open case of the remittance, then resumes or stops it at once.
     * Repeating it changes nothing. A BLOCK case can only be rejected.
     */
    public RemittanceView decide(UUID remittanceId, CaseDecision decision, String reason, String decidedBy) {
        Remittance r = repository.findByIdForUpdate(remittanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Remittance", remittanceId));
        // every case gets the decision: an open one is decided, one already decided the same way is left alone, and one already
        // decided the opposite way refuses (so a late opposite decision can never rewrite history)
        for (UUID caseId : new UUID[]{r.getSenderCaseId(), r.getRecipientCaseId(), r.getVelocityCaseId(), r.getFundingCaseId()}) {
            if (caseId != null) {
                compliance.decideCase(caseId, decision, reason, decidedBy);
            }
        }
        gate.resolve(r);
        return RemittanceMapper.view(r);
    }

    /** Re-checks one remittance whose cases may have been decided elsewhere. True if it moved. */
    public boolean resolve(UUID remittanceId) {
        Remittance r = repository.findByIdForUpdate(remittanceId).orElse(null);
        return r != null && gate.resolve(r);
    }
}
