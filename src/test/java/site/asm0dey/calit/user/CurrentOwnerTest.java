package site.asm0dey.calit.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CurrentOwnerTest {
    @Inject
    CurrentOwner currentOwner;

    @Test
    void unsetByDefaultAndRequireThrows401() {
        assertThat(currentOwner.isSet()).isFalse();
        WebApplicationException ex =
                assertThatExceptionOfType(WebApplicationException.class).isThrownBy(currentOwner::require).actual();
        assertThat(ex.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void setStoresOwnerAndExposesId() {
        AppUser u = new AppUser();
        u.id = 42L;
        currentOwner.set(u);
        assertThat(currentOwner.isSet()).isTrue();
        assertThat(currentOwner.get()).isSameAs(u);
        assertThat(currentOwner.require()).isSameAs(u);
        assertThat(currentOwner.id()).isEqualTo(42L);
    }
}
