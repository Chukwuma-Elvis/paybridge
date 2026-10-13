package com.academy.paybridge.security;

import com.academy.paybridge.security.domain.ApiKeyRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyAdminEndpointsTest extends AbstractSecurityTest {

    private static final String KEY_HEADER = "X-API-Key";

    private MockMvc mvc;
    private String admin;

    @BeforeEach
    void setUpSecuredMockMvcAndAnAdminKey() {
        mvc = secured();
        admin = newKey(ApiKeyRole.ADMIN);
    }

    private MockMvc mvc() {
        return mvc;
    }

    private String admin() {
        return admin;
    }

    private String createKeyJson(String name, String role) {
        return "{\"name\": \"" + name + "\", \"role\": \"" + role + "\"}";
    }

    @Test
    void anAdminCanCreateAKeyAndSeesItExactlyOnce() throws Exception {
        String response = mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("mobile-app", "CLIENT")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Location", startsWith("/api/admin/api-keys/")))
                .andExpect(jsonPath("$.name").value("mobile-app"))
                .andExpect(jsonPath("$.role").value("CLIENT"))
                .andExpect(jsonPath("$.apiKey").value(startsWith("pb_")))
                .andExpect(jsonPath("$.notice").value(containsString("cannot be shown again")))
                .andReturn().getResponse().getContentAsString();

        String createdKey = JsonPath.read(response, "$.apiKey");
        String id = JsonPath.read(response, "$.id");

        String listing = mvc().perform(get("/api/admin/api-keys").header(KEY_HEADER, admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).contains(id).doesNotContain(createdKey).doesNotContain("apiKey").doesNotContain("keyHash");
    }

    @Test
    void theNewKeyWorksStraightAway() throws Exception {
        String response = mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("shop", "CLIENT")))
                .andReturn().getResponse().getContentAsString();
        String newKey = JsonPath.read(response, "$.apiKey");

        mvc().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, newKey))
                .andExpect(status().isNotFound()); // let in

        mvc().perform(get("/api/admin/api-keys").header(KEY_HEADER, newKey))
                .andExpect(status().isForbidden()); // but it is only a client key
    }

    @Test
    void revokingAKeyStopsItWorkingAndRevokingAgainIsHarmless() throws Exception {
        String response = mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("leaked", "CLIENT")))
                .andReturn().getResponse().getContentAsString();
        String newKey = JsonPath.read(response, "$.apiKey");
        String id = JsonPath.read(response, "$.id");

        mvc().perform(delete("/api/admin/api-keys/" + id).header(KEY_HEADER, admin()))
                .andExpect(status().isNoContent());

        mvc().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, newKey))
                .andExpect(status().isUnauthorized());

        mvc().perform(delete("/api/admin/api-keys/" + id).header(KEY_HEADER, admin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void revokedKeysStayInTheListMarkedInactive() throws Exception {
        String response = mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("old-key", "CLIENT")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");
        mvc().perform(delete("/api/admin/api-keys/" + id).header(KEY_HEADER, admin()));

        String listing = mvc().perform(get("/api/admin/api-keys").header(KEY_HEADER, admin()))
                .andReturn().getResponse().getContentAsString();

        List<Boolean> active = JsonPath.read(listing, "$[?(@.id == '" + id + "')].active");
        assertThat(active).containsExactly(false);
    }

    @Test
    void revokingAnUnknownKeyIsNotFound() throws Exception {
        mvc().perform(delete("/api/admin/api-keys/" + UUID.randomUUID()).header(KEY_HEADER, admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void aBlankNameIsRejectedWithFieldErrors() throws Exception {
        mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("  ", "CLIENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void aNameWithOddCharactersIsRejected() throws Exception {
        mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("evil\\nname<script>", "CLIENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void aMissingRoleIsRejected() throws Exception {
        mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"no-role\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role").exists());
    }

    @Test
    void anUnknownRoleIsRejected() throws Exception {
        mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("odd-role", "SUPERUSER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aClientKeyCannotCreateKeys() throws Exception {
        mvc().perform(post("/api/admin/api-keys").header(KEY_HEADER, newKey(ApiKeyRole.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("sneaky", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingAKeyWithNoKeyIsRefused() throws Exception {
        mvc().perform(post("/api/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON).content(createKeyJson("anonymous", "ADMIN")))
                .andExpect(status().isUnauthorized());
    }
}
