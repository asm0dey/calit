package site.asm0dey.calit.crypto;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.google.GoogleCredential;

@QuarkusTest
class TokenEncryptionAtRestTest {
    @Inject
    EntityManager em;
    @Inject
    site.asm0dey.calit.crypto.TokenBackfill backfill;

    @Test
    @Transactional
    void refreshTokenIsCiphertextInTheRowButPlaintextViaEntity() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.googleSub = "sub-enc-test";
        c.refreshToken = "1//super-secret-refresh";
        c.accessToken = "ya29.access-secret";
        c.accessTokenExpiry = Instant.parse("2099-01-01T11:00:00Z");
        c.persist();
        c.flush();

        Object raw = em
            .createNativeQuery("select refresh_token from google_credential where id = :id")
            .setParameter("id", c.id)
            .getSingleResult();
        assertThat(raw.toString()).as("stored token must be encrypted").startsWith("enc:v1:");
        assertThat(raw.toString()).as("plaintext must not be at rest").doesNotContain("super-secret-refresh");

        GoogleCredential reloaded = GoogleCredential.findById(c.id);
        assertThat(reloaded.refreshToken).isEqualTo("1//super-secret-refresh");
        assertThat(reloaded.accessToken).isEqualTo("ya29.access-secret");
    }

    @Test
    @Transactional
    void backfillEncryptsLegacyPlaintextRow() {
        em
            .createNativeQuery(
                    "insert into google_credential "
                    + "(owner_id, refresh_token, access_token, google_sub, needs_reconnect) "
                    + "values (1, 'legacy-plain-refresh', 'legacy-plain-access', 'sub-legacy', false)"
            )
            .executeUpdate();

        backfill.encryptLegacy();

        Object raw = em
            .createNativeQuery("select refresh_token from google_credential where google_sub = 'sub-legacy'")
            .getSingleResult();
        assertThat(raw.toString()).as("legacy row must be encrypted after backfill").startsWith("enc:v1:");
    }
}
