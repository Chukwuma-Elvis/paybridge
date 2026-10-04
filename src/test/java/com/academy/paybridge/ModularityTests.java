package com.academy.paybridge;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

public class ModularityTests {
    @Test
    void verifyModuleStructure() {
        ApplicationModules.of(PayBridgeApplication.class).verify();
    }
}


