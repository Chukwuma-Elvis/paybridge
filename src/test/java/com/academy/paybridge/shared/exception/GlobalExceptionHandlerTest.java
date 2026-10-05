package com.academy.paybridge.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void turnsAPayBridgeExceptionIntoItsStatusAndCode() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Account 123 not found"));
    }

    @Test
    void usesTheStatusOfWhicheverCodeWasThrown() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void hidesTheDetailsOfUnexpectedErrors() throws Exception {
        String body = mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("hunter2");
    }

    @RestController
    static class FailingController {

        @GetMapping("/test/not-found")
        String notFound() {
            throw new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "Account 123 not found");
        }

        @GetMapping("/test/conflict")
        String conflict() {
            throw new PayBridgeException(ErrorCode.CONFLICT, "Already exists");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("database password is hunter2");
        }
    }
}