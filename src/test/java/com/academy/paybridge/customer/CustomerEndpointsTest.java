package com.academy.paybridge.customer;

import com.academy.paybridge.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerEndpointsTest extends AbstractIntegrationTest {

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String customerJson(String email, String phone) {
        return """
                {"firstName": "Ada", "lastName": "Obi", "email": "%s", "phone": "%s"}
                """.formatted(email, phone);
    }

    private MvcResult createCustomer(String email, String phone) throws Exception {
        return mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(email, phone)))
                .andReturn();
    }

    @Test
    void createsACustomerWhoStartsUnverifiedAndActive() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(email, "08012345678")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.kycTier").value("UNVERIFIED"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.phone").value("+2348012345678"));
    }

    @Test
    void storesEmailInLowerCase() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(email.toUpperCase(), "08012345678")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));
    }

    @Test
    void rejectsADuplicateEmailEvenWithDifferentCase() throws Exception {
        String email = uniqueEmail();
        createCustomer(email, "08012345678");

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(email.toUpperCase(), "08098765432")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void reportsEveryInvalidFieldInOneResponse() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName": "", "lastName": "Obi", "email": "not-an-email", "phone": "12345"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.firstName").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.phone").exists())
                .andExpect(jsonPath("$.errors.lastName").doesNotExist());
    }

    @Test
    void fetchesAnExistingCustomer() throws Exception {
        String email = uniqueEmail();
        String id = JsonPath.read(createCustomer(email, "07012345678").getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/customers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void answersNotFoundForAnUnknownCustomer() throws Exception {
        mockMvc.perform(get("/api/customers/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void changesTheKycTier() throws Exception {
        String id = JsonPath.read(
                createCustomer(uniqueEmail(), "09012345678").getResponse().getContentAsString(), "$.id");

        mockMvc.perform(patch("/api/customers/" + id + "/kyc-tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kycTier\": \"FULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycTier").value("FULL"));

        mockMvc.perform(get("/api/customers/" + id))
                .andExpect(jsonPath("$.kycTier").value("FULL"));
    }

    @Test
    void rejectsAnUnknownKycTierName() throws Exception {
        String id = JsonPath.read(
                createCustomer(uniqueEmail(), "09012345678").getResponse().getContentAsString(), "$.id");

        MvcResult result = mockMvc.perform(patch("/api/customers/" + id + "/kyc-tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kycTier\": \"GOLD\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}
