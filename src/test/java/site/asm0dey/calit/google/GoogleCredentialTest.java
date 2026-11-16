package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleCredentialTest {
    @Test
    @TestTransaction
    void getReturnsNullWhenNotConnected() {
        assertThat(GoogleCredential.forOwner(1L)).isNull();
    }

    @Test
    @Transactional
    void multipleAccountsPerOwnerAreFoundByOwnerAndSub() {
        GoogleCredential a = new GoogleCredential();
        a.ownerId = 1L;
        a.refreshToken = "rt-A";
        a.googleSub = "sub-A";
        a.accountEmail = "a@example.com";
        a.persist();
        GoogleCredential b = new GoogleCredential();
        b.ownerId = 1L;
        b.refreshToken = "rt-B";
        b.googleSub = "sub-B";
        b.accountEmail = "b@example.com";
        b.persist();

        assertThat(GoogleCredential.countForOwner(1L)).isEqualTo(2);
        assertThat(GoogleCredential.findByOwnerAndSub(1L, "sub-A").accountEmail).isEqualTo("a@example.com");
        assertThat(GoogleCredential.findByOwnerAndSub(1L, "sub-missing")).isNull();
    }
}
