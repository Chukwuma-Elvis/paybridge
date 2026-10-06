package com.academy.paybridge.customer.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String firstName,

        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String lastName,

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "^(\\+234|0)[789][01]\\d{8}$",
                message = "must be a Nigerian mobile number such as 08012345678 or +2348012345678")
        String phone) {
}
