package com.academy.paybridge.customer.service;

import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.customer.api.KycTier;
import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.customer.repository.CustomerRepository;
import com.academy.paybridge.shared.exception.ConflictException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class CustomerService implements CustomerApi {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public CustomerSummary register(String firstName, String lastName, String email, String phone) {
        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (customerRepository.existsByEmail(normalisedEmail)) {
            throw emailTaken(normalisedEmail);
        }

        Customer customer = new Customer(firstName.trim(), lastName.trim(), normalisedEmail, normalisePhone(phone));
        try {
            // flush now so a unique-constraint race with another request surfaces here, not at commit time
            customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken(normalisedEmail);
        }
        return toSummary(customer);
    }

    public CustomerSummary changeKycTier(UUID customerId, KycTier newTier) {
        Customer customer = find(customerId);
        customer.changeKycTier(newTier);
        return toSummary(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerSummary getCustomer(UUID customerId) {
        return toSummary(find(customerId));
    }

    private Customer find(UUID customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
    }

    private static ConflictException emailTaken(String email) {
        return new ConflictException("A customer with email " + email + " already exists");
    }

    /** Accepts 080... or +23480... and stores the +234 form so every phone looks the same. */
    static String normalisePhone(String phone) {
        String trimmed = phone.trim();
        if (trimmed.startsWith("0")) {
            return "+234" + trimmed.substring(1);
        }
        return trimmed;
    }

    private static CustomerSummary toSummary(Customer customer) {
        return new CustomerSummary(
                customer.getId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getKycTier(),
                customer.getStatus());
    }
}
