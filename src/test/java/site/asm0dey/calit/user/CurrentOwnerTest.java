package site.asm0dey.calit.user;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CurrentOwnerTest {

    @Inject
    CurrentOwner currentOwner;

    @Test
    void unsetByDefaultAndRequireThrows401() {
        assertFalse(currentOwner.isSet());
        WebApplicationException ex = assertThrows(WebApplicationException.class, currentOwner::require);
        assertEquals(401, ex.getResponse().getStatus());
    }

    @Test
    void setStoresOwnerAndExposesId() {
        AppUser u = new AppUser();
        u.id = 42L;
        currentOwner.set(u);
        assertTrue(currentOwner.isSet());
        assertSame(u, currentOwner.get());
        assertSame(u, currentOwner.require());
        assertEquals(42L, currentOwner.id());
    }
}
