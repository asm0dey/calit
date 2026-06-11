package com.calit.user;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

/**
 * Test-only baseline seed. The {@link FirstRunRedirectFilter} redirects every request to
 * {@code /setup} while no {@code app_user} exists. Many existing tests exercise public/API/health
 * endpoints and seed domain data directly without ever creating a user, so on a fresh shared test
 * DB they would be hijacked to {@code /setup}. This startup observer guarantees the test instance
 * is "bootstrapped" (>= 1 user) so the filter is a no-op for everyone EXCEPT {@code SetupFlowTest},
 * which deletes users inside its own methods to exercise the first-run path and restores the
 * baseline afterwards.
 *
 * <p>Lives in test sources only — production bootstrapping happens through {@code /setup}.</p>
 */
@ApplicationScoped
public class TestUserBootstrap {

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        if (AppUser.count() == 0) {
            AppUser.create("admin", new PasswordHasher().hash("testpass"), true).persist();
        }
    }
}
