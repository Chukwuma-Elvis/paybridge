package com.academy.paybridge.transfer;

import com.academy.paybridge.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BeneficiaryTest extends AbstractIntegrationTest {

    private static String createBody(UUID customerId, String bankCode, String accountNumber) {
        return """
                {"customerId": "%s", "bankCode": "%s", "accountNumber": "%s"}
                """.formatted(customerId, bankCode, accountNumber);
    }

    @Test
    void nameEnquiryShowsWhoseAccountItIs() throws Exception {
        mockMvc.perform(post("/api/beneficiaries/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\": \"058\", \"accountNumber\": \"0123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("TEST ACCOUNT 6789"))
                .andExpect(jsonPath("$.accountNumber").value("0123456789"));
    }

    @Test
    void anAccountThatCannotBeResolvedIsNotFound() throws Exception {
        mockMvc.perform(post("/api/beneficiaries/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\": \"058\", \"accountNumber\": \"0123459999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void savesAPayeeWithTheNameTheBankGave() throws Exception {
        UUID customer = createCustomer();

        mockMvc.perform(post("/api/beneficiaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer, "058", "0123456789")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.accountName").value("TEST ACCOUNT 6789"));
    }

    @Test
    void savingTheSamePayeeTwiceGivesTheSameRecord() throws Exception {
        UUID customer = createCustomer();
        String body = createBody(customer, "058", "0123456789");

        String first = mockMvc.perform(post("/api/beneficiaries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/beneficiaries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        mockMvc.perform(get("/api/beneficiaries").param("customerId", customer.toString()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void twoCustomersCanSaveTheSameBankAccount() throws Exception {
        UUID one = createCustomer();
        UUID two = createCustomer();

        String first = mockMvc.perform(post("/api/beneficiaries").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(one, "058", "0123456789"))).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/beneficiaries").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(two, "058", "0123456789"))).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isNotEqualTo(JsonPath.read(first, "$.id"));
    }

    @Test
    void anUnresolvablePayeeIsNotSaved() throws Exception {
        UUID customer = createCustomer();

        mockMvc.perform(post("/api/beneficiaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer, "058", "0123459999")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/beneficiaries").param("customerId", customer.toString()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anUnknownCustomerCannotSavePayees() throws Exception {
        mockMvc.perform(post("/api/beneficiaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), "058", "0123456789")))
                .andExpect(status().isNotFound());
    }

    @Test
    void badBankCodesAndAccountNumbersAreRejected() throws Exception {
        UUID customer = createCustomer();

        mockMvc.perform(post("/api/beneficiaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer, "ab", "12345")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bankCode").exists())
                .andExpect(jsonPath("$.errors.accountNumber").exists());
    }
}
