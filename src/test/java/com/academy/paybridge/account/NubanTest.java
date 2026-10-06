package com.academy.paybridge.account;

import com.academy.paybridge.account.domain.Nuban;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NubanTest {

    // Worked by hand with the NUBAN weights 3,7,3,3,7,3,3,7,3,3,7,3
    @ParameterizedTest
    @CsvSource({
            "000, 000000000, 0",   // sum 0  -> 10 - 0 = 10 -> 0
            "000, 000000001, 7",   // sum 3  -> 10 - 3 = 7
            "000, 000000010, 3",   // sum 7  -> 10 - 7 = 3
            "000, 000000100, 7",   // sum 3  -> 7   (digit 1 at position 10, weight 3)
            "100, 000000000, 7"    // sum 3  -> 7   (digit 1 at position 1, weight 3)
    })
    void calculatesTheCheckDigit(String bankCode, String serial, int expected) {
        assertThat(Nuban.checkDigit(bankCode, serial)).isEqualTo(expected);
    }

    @Test
    void generatesATenDigitNumberStartingWithTheSerial() {
        String number = Nuban.generate("999", 100000001L);

        assertThat(number).hasSize(10).startsWith("100000001").matches("\\d{10}");
    }

    @Test
    void generatedNumbersPassValidation() {
        for (long serial = 100000001L; serial < 100000201L; serial++) {
            assertThat(Nuban.isValid("999", Nuban.generate("999", serial))).isTrue();
        }
    }

    @Test
    void aNumberFromAnotherBankFailsValidation() {
        String number = Nuban.generate("999", 100000001L);

        // the same ten digits are (almost always) invalid under a different bank code
        long failures = java.util.stream.IntStream.range(100, 200)
                .filter(code -> !Nuban.isValid(String.valueOf(code), number))
                .count();
        assertThat(failures).isGreaterThan(80);
    }

    @Test
    void aSingleMistypedDigitIsAlwaysDetected() {
        String number = Nuban.generate("999", 123456789L);

        for (int position = 0; position < 9; position++) {
            char original = number.charAt(position);
            for (char replacement = '0'; replacement <= '9'; replacement++) {
                if (replacement == original) {
                    continue;
                }
                String mistyped = number.substring(0, position) + replacement + number.substring(position + 1);
                assertThat(Nuban.isValid("999", mistyped))
                        .as("changing digit %d from %s to %s", position, original, replacement)
                        .isFalse();
            }
        }
    }

    @Test
    void rejectsMalformedNumbers() {
        assertThat(Nuban.isValid("999", null)).isFalse();
        assertThat(Nuban.isValid("999", "12345")).isFalse();
        assertThat(Nuban.isValid("999", "12345678ab")).isFalse();
    }

    @Test
    void rejectsABadBankCodeOrSerial() {
        assertThatThrownBy(() -> Nuban.checkDigit("99", "123456789")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Nuban.checkDigit("999", "12345")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Nuban.generate("999", 1_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
    }
}
