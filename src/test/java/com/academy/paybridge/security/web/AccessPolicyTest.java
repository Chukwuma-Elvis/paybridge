package com.academy.paybridge.security.web;

import com.academy.paybridge.security.web.AccessPolicy.Requirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AccessPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "/actuator/health,           PUBLIC",
            "/actuator/health/liveness,  PUBLIC",
            "/actuator/health/readiness, PUBLIC",
            "/actuator/health/,          PUBLIC",
            "/api/customers,             ANY_KEY",
            "/api/accounts/by-number/1,  ANY_KEY",
            "/api/transfers/internal,    ANY_KEY",
            "/api/transfers/external,    ANY_KEY",
            "/api/notifications,         ANY_KEY",
            "/,                          ANY_KEY",
            "/something/unknown,         ANY_KEY",
            "/api/admin/api-keys,        ADMIN",
            "/api/admin,                 ADMIN",
            "/api/dev/accounts/1/fund,   ADMIN",
            "/actuator/metrics,          ADMIN",
            "/actuator/env,              ADMIN",
            "/actuator,                  ADMIN",
            "/api/webhooks/paystack,     SIGNED_WEBHOOK",
            "/api/webhooks/monnify,      SIGNED_WEBHOOK",
            "/api/webhooks,              SIGNED_WEBHOOK",
            "/api/webhooksx/paystack,    ANY_KEY"
    })
    void eachAddressNeedsTheRightKind(String path, Requirement expected) {
        assertThat(AccessPolicy.requirementFor(path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/actuator/health/../metrics        | ADMIN",
            "/actuator/health/./../metrics      | ADMIN",
            "/api/customers/../admin/api-keys   | ADMIN",
            "/api/./dev/accounts/1/fund         | ADMIN",
            "/API/DEV/accounts/1/fund           | ADMIN",
            "/Api/Admin/Api-Keys                | ADMIN",
            "/api//dev/accounts/1/fund          | ADMIN",
            "/api///admin/api-keys              | ADMIN",
            "/api/dev;jsessionid=1/accounts     | ADMIN",
            "/api/%64ev/accounts/1/fund         | ADMIN",
            "/api/%61dmin/api-keys              | ADMIN",
            "/actuator/%6Detrics                | ADMIN",
            "/api\\dev\\accounts                | ADMIN",
            "/actuator//health                  | PUBLIC",
            "/ACTUATOR/HEALTH                   | PUBLIC",
            "/API//Webhooks/Paystack            | SIGNED_WEBHOOK",
            "/api/%77ebhooks/paystack           | SIGNED_WEBHOOK",
            "/api/webhooks/../admin/api-keys    | ADMIN",
            "/api/webhooks/../dev/accounts/1    | ADMIN",
            "/api/customers/../webhooks/x       | SIGNED_WEBHOOK"
    })
    void disguisedAddressesAreJudgedByWhereTheyReallyGo(String path, Requirement expected) {
        assertThat(AccessPolicy.requirementFor(path)).isEqualTo(expected);
    }

    @Test
    void healthLookalikesAreNotPublic() {
        assertThat(AccessPolicy.requirementFor("/actuator/health/anything-else")).isEqualTo(Requirement.ADMIN);
        assertThat(AccessPolicy.requirementFor("/actuator/healthz")).isEqualTo(Requirement.ADMIN);
        assertThat(AccessPolicy.requirementFor("/actuator/health.json")).isEqualTo(Requirement.ADMIN);
    }

    @Test
    void anAddressWeCannotUnderstandNeedsAnAdminKey() {
        assertThat(AccessPolicy.requirementFor("/api/%zz")).isEqualTo(Requirement.ADMIN);
        assertThat(AccessPolicy.requirementFor(null)).isEqualTo(Requirement.ADMIN);
    }

    @Test
    void dotDotCannotClimbAboveTheRoot() {
        assertThat(AccessPolicy.normalise("/../../actuator/metrics")).isEqualTo("/actuator/metrics");
    }
}
