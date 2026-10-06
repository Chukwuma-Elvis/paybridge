package com.academy.paybridge;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base class for tests that need the whole application and a real Postgres.
 * All subclasses share one Spring context (and therefore one container), so tests must use
 * their own unique data rather than assume an empty database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected WebApplicationContext webApplicationContext;

    protected MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /** Registers a brand-new customer through the real endpoint and returns the id. */
    protected UUID createCustomer() throws Exception {
        String body = """
                {"firstName": "Test", "lastName": "User", "email": "user-%s@example.com", "phone": "08012345678"}
                """.formatted(UUID.randomUUID());
        String json = mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    /** Opens an NGN account for the customer through the real endpoint and returns the account id. */
    protected UUID openAccount(UUID customerId) throws Exception {
        String json = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": \"" + customerId + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    /** A new customer with a new empty account. */
    protected UUID newAccount() throws Exception {
        return openAccount(createCustomer());
    }

    /** Puts test naira into an account through the dev funding endpoint. */
    protected void fundAccount(UUID accountId, long amountMinor) throws Exception {
        mockMvc.perform(post("/api/dev/accounts/" + accountId + "/fund")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\": " + amountMinor + "}"));
    }

    /** A new customer with a new account already holding {@code amountMinor} kobo. */
    protected UUID newFundedAccount(long amountMinor) throws Exception {
        UUID accountId = newAccount();
        fundAccount(accountId, amountMinor);
        return accountId;
    }
}
