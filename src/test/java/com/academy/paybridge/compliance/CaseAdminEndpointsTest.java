package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.security.AbstractSecurityTest;
import com.academy.paybridge.security.domain.ApiKeyRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The reviewer's desk over HTTP, through the real security filters. */
class CaseAdminEndpointsTest extends AbstractSecurityTest {

    private static final String KEY = "X-API-Key";

    @Autowired ComplianceApi compliance;

    private MockMvc mvc;
    private String admin;
    private String client;
    private UUID caseId;

    @BeforeEach
    void anOpenCaseAndTwoKeys() {
        mvc = secured();
        admin = newKey(ApiKeyRole.ADMIN);
        client = newKey(ApiKeyRole.CLIENT);
        caseId = compliance.screen(new Party("Alina Samplova", "NG", null), "SENDER", "CTX-" + UUID.randomUUID()).caseId();
    }

    @Test
    void onlyAnAdminMayReadOrDecideCases() throws Exception {
        mvc.perform(get("/api/admin/compliance/cases").header(KEY, client)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/compliance/cases/" + caseId).header(KEY, client)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/compliance/cases/" + caseId + "/decision").header(KEY, client)
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\",\"reason\":\"ok\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminSeesTheOpenCaseAndDecidesIt() throws Exception {
        mvc.perform(get("/api/admin/compliance/cases").header(KEY, admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/compliance/cases/" + caseId).header(KEY, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));

        mvc.perform(post("/api/admin/compliance/cases/" + caseId + "/decision").header(KEY, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\",\"reason\":\"Birth dates differ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(org.hamcrest.Matchers.containsString("test-admin")));
    }

    @Test
    void aDecisionWithoutAReasonIsRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/admin/compliance/cases/" + caseId + "/decision").header(KEY, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        mvc.perform(post("/api/admin/compliance/cases/" + caseId + "/decision").header(KEY, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.decision").exists());
    }

    @Test
    void theOppositeDecisionAfterwardsIsAConflict() throws Exception {
        mvc.perform(post("/api/admin/compliance/cases/" + caseId + "/decision").header(KEY, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\",\"reason\":\"fine\"}"));

        mvc.perform(post("/api/admin/compliance/cases/" + caseId + "/decision").header(KEY, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\",\"reason\":\"no\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownCaseIsNotFound() throws Exception {
        mvc.perform(get("/api/admin/compliance/cases/" + UUID.randomUUID()).header(KEY, admin)).andExpect(status().isNotFound());
    }
}
