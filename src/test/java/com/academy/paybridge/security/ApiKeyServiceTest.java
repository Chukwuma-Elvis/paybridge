package com.academy.paybridge.security;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.security.domain.ApiKeyRole;
import com.academy.paybridge.security.service.ApiKeyHasher;
import com.academy.paybridge.security.service.ApiKeyService;
import com.academy.paybridge.security.service.ApiKeyView;
import com.academy.paybridge.security.service.AuthenticatedClient;
import com.academy.paybridge.security.service.CreatedApiKey;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aNewKeyIsLongRandomAndPrefixed() {
        CreatedApiKey created = apiKeyService.create("mobile-app", ApiKeyRole.CLIENT);

        assertThat(created.plaintext()).startsWith("pb_").hasSize(46).matches("[A-Za-z0-9_-]+");
        assertThat(created.key().prefix()).isEqualTo(created.plaintext().substring(0, 11));
        assertThat(created.key().role()).isEqualTo(ApiKeyRole.CLIENT);
        assertThat(created.key().active()).isTrue();
    }

    @Test
    void everyKeyIsDifferent() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            keys.add(apiKeyService.create("k" + i, ApiKeyRole.CLIENT).plaintext());
        }

        assertThat(keys).hasSize(50);
    }

    @Test
    void onlyTheHashIsStoredNeverTheKeyItself() {
        CreatedApiKey created = apiKeyService.create("hash-check", ApiKeyRole.CLIENT);

        String storedHash = jdbc.queryForObject("select key_hash from api_key where id = ?", String.class, created.key().id());
        assertThat(storedHash).hasSize(64).matches("[0-9a-f]+").isEqualTo(ApiKeyHasher.sha256Hex(created.plaintext()));

        Integer rowsContainingThePlainKey = jdbc.queryForObject(
                "select count(*) from api_key where key_hash = ? or key_prefix = ? or name = ?",
                Integer.class, created.plaintext(), created.plaintext(), created.plaintext());
        assertThat(rowsContainingThePlainKey).isZero();
    }

    @Test
    void theStoredPrefixIsOnlyTheFirstFewCharacters() {
        CreatedApiKey created = apiKeyService.create("prefix-check", ApiKeyRole.CLIENT);

        String storedPrefix = jdbc.queryForObject("select key_prefix from api_key where id = ?", String.class, created.key().id());
        assertThat(storedPrefix).hasSize(11);
        assertThat(created.plaintext()).startsWith(storedPrefix).isNotEqualTo(storedPrefix);
    }

    @Test
    void aValidKeyIdentifiesItsOwner() {
        CreatedApiKey created = apiKeyService.create("partner-bank", ApiKeyRole.ADMIN);

        Optional<AuthenticatedClient> client = apiKeyService.authenticate(created.plaintext());

        assertThat(client).isPresent();
        assertThat(client.get().keyId()).isEqualTo(created.key().id());
        assertThat(client.get().name()).isEqualTo("partner-bank");
        assertThat(client.get().isAdmin()).isTrue();
    }

    @Test
    void aClientKeyIsNotAnAdmin() {
        CreatedApiKey created = apiKeyService.create("shop", ApiKeyRole.CLIENT);

        assertThat(apiKeyService.authenticate(created.plaintext()).orElseThrow().isAdmin()).isFalse();
    }

    @Test
    void anUnknownKeyIsRefused() {
        assertThat(apiKeyService.authenticate("pb_" + "x".repeat(43))).isEmpty();
    }

    @Test
    void aKeyWithOneCharacterChangedIsRefused() {
        String real = apiKeyService.create("typo", ApiKeyRole.CLIENT).plaintext();
        String altered = real.substring(0, real.length() - 1) + (real.endsWith("A") ? "B" : "A");

        assertThat(apiKeyService.authenticate(altered)).isEmpty();
    }

    @Test
    void theStoredHashIsNotAKeyThatWorks() {
        CreatedApiKey created = apiKeyService.create("stolen-table", ApiKeyRole.ADMIN);
        String stolenHash = ApiKeyHasher.sha256Hex(created.plaintext());

        assertThat(apiKeyService.authenticate(stolenHash)).isEmpty();
    }

    @Test
    void junkIsRefusedWithoutTroublingTheDatabase() {
        assertThat(apiKeyService.authenticate(null)).isEmpty();
        assertThat(apiKeyService.authenticate("")).isEmpty();
        assertThat(apiKeyService.authenticate("   ")).isEmpty();
        assertThat(apiKeyService.authenticate("short")).isEmpty();
        assertThat(apiKeyService.authenticate("has spaces in it which is not a valid key at all")).isEmpty();
        assertThat(apiKeyService.authenticate("pb_'; drop table api_key; --")).isEmpty();
        assertThat(apiKeyService.authenticate("x".repeat(500))).isEmpty();
    }

    @Test
    void aRevokedKeyStopsWorking() {
        CreatedApiKey created = apiKeyService.create("leaked", ApiKeyRole.CLIENT);
        assertThat(apiKeyService.authenticate(created.plaintext())).isPresent();

        ApiKeyView revoked = apiKeyService.revoke(created.key().id());

        assertThat(revoked.active()).isFalse();
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(apiKeyService.authenticate(created.plaintext())).isEmpty();
    }

    @Test
    void revokingTwiceChangesNothing() {
        CreatedApiKey created = apiKeyService.create("twice", ApiKeyRole.CLIENT);

        apiKeyService.revoke(created.key().id());
        Instant afterFirst = revokedAtAsStored(created.key().id());

        apiKeyService.revoke(created.key().id());

        assertThat(revokedAtAsStored(created.key().id())).isEqualTo(afterFirst);
    }

    /** The timestamp as the database holds it (read back, so precision is the database's, not the JVM's). */
    private Instant revokedAtAsStored(UUID id) {
        return apiKeyService.list().stream().filter(view -> view.id().equals(id)).findFirst().orElseThrow().revokedAt();
    }

    @Test
    void revokingAnUnknownKeyIsNotFound() {
        assertThatThrownBy(() -> apiKeyService.revoke(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theListShowsEveryKeyButNeverASecret() {
        CreatedApiKey created = apiKeyService.create("listed", ApiKeyRole.CLIENT);

        ApiKeyView listed = apiKeyService.list().stream()
                .filter(view -> view.id().equals(created.key().id())).findFirst().orElseThrow();

        assertThat(listed.name()).isEqualTo("listed");
        assertThat(listed.prefix()).isEqualTo(created.key().prefix());
        assertThat(listed.toString()).doesNotContain(created.plaintext());
    }

    @Test
    void anOperatorChosenKeyIsRegisteredOnceOnly() {
        String chosen = "operator-chosen-key-" + UUID.randomUUID().toString().replace("-", "");

        assertThat(apiKeyService.ensureKey(chosen, "bootstrap-test", ApiKeyRole.ADMIN)).isTrue();
        assertThat(apiKeyService.ensureKey(chosen, "bootstrap-test", ApiKeyRole.ADMIN)).isFalse();

        assertThat(apiKeyService.authenticate(chosen)).isPresent();
        assertThat(apiKeyService.hasActiveAdminKey()).isTrue();
    }

    @Test
    void anOperatorChosenKeyShowsNoneOfItsOwnCharactersInTheList() {
        String chosen = "operator-chosen-key-" + UUID.randomUUID().toString().replace("-", "");
        apiKeyService.ensureKey(chosen, "bootstrap-test", ApiKeyRole.ADMIN);
        UUID id = apiKeyService.authenticate(chosen).orElseThrow().keyId();

        ApiKeyView listed = apiKeyService.list().stream().filter(view -> view.id().equals(id)).findFirst().orElseThrow();

        assertThat(listed.prefix()).isEqualTo("(operator)");
        assertThat(chosen).doesNotContain(listed.prefix());
    }

    @Test
    void aRevokedOperatorKeyIsNotQuietlyBroughtBack() {
        String chosen = "operator-chosen-key-" + UUID.randomUUID().toString().replace("-", "");
        apiKeyService.ensureKey(chosen, "bootstrap-test", ApiKeyRole.ADMIN);
        UUID id = apiKeyService.authenticate(chosen).orElseThrow().keyId();

        apiKeyService.revoke(id);

        assertThat(apiKeyService.ensureKey(chosen, "bootstrap-test", ApiKeyRole.ADMIN)).isFalse();
        assertThat(apiKeyService.authenticate(chosen)).isEmpty();
    }

    @Test
    void shapeCheckAcceptsOnlyKeyLikeText() {
        assertThat(ApiKeyService.hasValidShape("pb_" + "a".repeat(43))).isTrue();
        assertThat(ApiKeyService.hasValidShape("short")).isFalse();
        assertThat(ApiKeyService.hasValidShape("has a space " + "a".repeat(30))).isFalse();
        assertThat(ApiKeyService.hasValidShape(null)).isFalse();
    }
}
