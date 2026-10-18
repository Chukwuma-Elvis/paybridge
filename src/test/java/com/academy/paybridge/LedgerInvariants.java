package com.academy.paybridge;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The money rule every test that moves money must end with: <b>within each currency, all account balances add up to exactly zero</b>.
 * <p>
 * Before USD existed this was written as "all balances add up to zero". With two currencies that is no longer true, and was never
 * the real rule: naira and dollars do not cancel each other out. Each currency is its own closed book.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    /** Fails if any currency's balances do not sum to zero. */
    public static void everyCurrencySumsToZero(JdbcTemplate jdbc) {
        List<Map<String, Object>> totals = jdbc.queryForList(
                "select currency, coalesce(sum(balance_minor), 0) as total from account group by currency");
        for (Map<String, Object> row : totals) {
            assertThat(((Number) row.get("total")).longValue())
                    .as("money must never be created or destroyed: %s balances must sum to zero", row.get("currency"))
                    .isZero();
        }
    }

    /** The total of one currency's balances (zero when the books are right). */
    public static long totalFor(JdbcTemplate jdbc, String currency) {
        Long total = jdbc.queryForObject(
                "select coalesce(sum(balance_minor), 0) from account where currency = ?", Long.class, currency);
        return total == null ? 0 : total;
    }
}
