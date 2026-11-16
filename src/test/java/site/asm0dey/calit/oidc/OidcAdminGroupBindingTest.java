package site.asm0dey.calit.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@link OidcSignInServiceTest} builds its service by hand, so this proves the key still binds.
 */
@QuarkusComponentTest
@TestConfigProperty(key = "calit.oidc.admin-group", value = "calit-admins")
class OidcAdminGroupBindingTest {
    @Inject
    OidcSignInService service;

    @Test
    void adminGroupBinds() {
        assertThat(ClientProxy.unwrap(service).adminGroup).contains("calit-admins");
    }
}
