package com.academy.paybridge.account;

import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerStatus;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.customer.api.KycTier;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** USD customer accounts are off unless {@code paybridge.fx.usd-accounts-enabled} is switched on. */
class UsdAccountSwitchTest {

    private final UUID customerId = UUID.randomUUID();
    private AccountRepository repository;
    private CustomerApi customers;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AccountRepository.class);
        customers = Mockito.mock(CustomerApi.class);
        when(customers.getCustomer(customerId)).thenReturn(
                new CustomerSummary(customerId, "Ada", "Obi", "ada@example.com", "+2348012345678", KycTier.BASIC, CustomerStatus.ACTIVE));
        when(repository.nextAccountSerial()).thenReturn(100_000_001L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AccountService service(boolean usdEnabled) {
        return new AccountService(repository, customers, "999", usdEnabled);
    }

    @Test
    void withTheSwitchOffADollarAccountIsRefusedAndNothingIsSaved() {
        assertThatThrownBy(() -> service(false).openCustomerAccount(customerId, "USD"))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("Only NGN accounts are supported")
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repository, never()).save(any());
    }

    @Test
    void withTheSwitchOffANairaAccountStillOpens() {
        AccountSummary opened = service(false).openCustomerAccount(customerId, "NGN");

        assertThat(opened.currency().getCurrencyCode()).isEqualTo("NGN");
    }

    @Test
    void withTheSwitchOnADollarAccountOpens() {
        AccountSummary opened = service(true).openCustomerAccount(customerId, "USD");

        assertThat(opened.currency().getCurrencyCode()).isEqualTo("USD");
        assertThat(opened.accountNumber()).hasSize(10);
        assertThat(opened.balanceMinor()).isZero();
    }

    @Test
    void withTheSwitchOnNairaStillOpensToo() {
        assertThat(service(true).openCustomerAccount(customerId, "NGN").currency().getCurrencyCode()).isEqualTo("NGN");
    }

    @Test
    void anyOtherCurrencyIsRefusedWhicheverWayTheSwitchIsSet() {
        for (boolean enabled : new boolean[]{false, true}) {
            assertThatThrownBy(() -> service(enabled).openCustomerAccount(customerId, "EUR"))
                    .isInstanceOf(PayBridgeException.class);
        }
    }
}
