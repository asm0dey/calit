package site.asm0dey.calit.test;

import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import jakarta.persistence.EntityManager;

/**
 * Gives every {@code @QuarkusTest} method a clean, known database even though {@code reuseForks=true}
 * shares ONE Postgres container + app across the whole suite. Before each test method it truncates
 * all domain tables and reseeds the baseline {@code admin} user.
 *
 * <p>{@code RESTART IDENTITY} makes ids deterministic: the reseeded admin is always {@code id = 1},
 * so tests can stamp owner-scoped rows with {@code ownerId = 1L}. Tests that need more data seed it
 * on top of this baseline; {@code SetupFlowTest} deletes the admin inside its own methods to exercise
 * the first-run path.</p>
 *
 * <p>Registered via {@code META-INF/services/io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback}.
 * The callback only fires for {@code @QuarkusTest} classes; plain unit tests (no CDI/ORM) are skipped.</p>
 */
public class DatabaseResetCallback implements QuarkusTestBeforeEachCallback {

    // Hash once per JVM (argon2 is deliberately slow); the same encoded hash verifies "testpass".
    private static final String ADMIN_HASH = new PasswordHasher().hash("testpass");

    private static final String TRUNCATE_ALL = "TRUNCATE TABLE "
            + "reminder, booking, date_override_window, date_override, availability_rule, "
            + "booking_field, meeting_type, owner_settings, google_calendar, google_credential, "
            + "app_user RESTART IDENTITY CASCADE";

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        if (Arc.container() == null || !Arc.container().instance(EntityManager.class).isAvailable()) {
            return; // not a Quarkus/ORM test context — nothing to reset
        }
        QuarkusTransaction.requiringNew().run(() -> {
            EntityManager em = Arc.container().instance(EntityManager.class).get();
            em.createNativeQuery(TRUNCATE_ALL).executeUpdate();
            AppUser admin = AppUser.create("admin", ADMIN_HASH, true);
            admin.settingsComplete = true; // baseline admin is onboarded (no first-login wizard in tests)
            admin.persist();
        });
    }
}
