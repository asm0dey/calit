package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.Transactional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * {@code /} is the instance's front door and the cheapest thing a browser does, so its data access
 * is pinned here rather than left to drift. The redirect preference resolves the account and reads
 * its settings row in ONE statement (a subquery over {@code app_user}); doing it as two separate
 * finds cost a round trip each, and nothing about the feature needs the {@code AppUser} object.
 *
 * <p>Scope of the numbers below: {@code @TestSecurity} installs the identity directly, so
 * {@code EnabledUserAugmentor}'s own per-request {@code app_user} enabled-check does not run here
 * and is not part of these counts. What these assert is the request handler's own data access —
 * which is what this code owns and what a regression would change.
 *
 * <p>Pattern and the {@code %test} {@code quarkus.hibernate-orm.statistics=true} switch both follow
 * {@code SlotServiceQueryCountTest}.
 */
@QuarkusTest
class HomeRedirectQueryCountTest {
    @Inject
    EntityManagerFactory emf;

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Transactional
    void seedOwnerSettings() {
        OwnerSettings.seed(1L, "admin@example.com");
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void redirectingVisitorCostsOneStatement() {
        seedOwnerSettings();

        Statistics statistics = stats();
        statistics.clear();
        given().redirects().follow(false).when().get("/").then().statusCode(303);

        assertEquals(
                1,
                statistics.getPrepareStatementCount(),
                "the redirect preference must resolve in one statement, not an app_user find plus an" + " owner_settings find"
        );
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void visitorWithNoSettingsRowAlsoCostsOneStatement() {
        // Deliberately not seeded. The no-row case must not degrade into a second probing query.
        Statistics statistics = stats();
        statistics.clear();
        given().redirects().follow(false).when().get("/").then().statusCode(200);

        assertEquals(1, statistics.getPrepareStatementCount(), "a missing settings row must still cost one statement");
    }

    @Test
    void anonymousVisitorTouchesTheDatabaseNotAtAll() {
        // The common case for the marketing page: no identity, so no preference to look up. The
        // product page is a pure template render, which is also why Cache-Control: private is
        // enough and no shared caching is wanted.
        Statistics statistics = stats();
        statistics.clear();
        given().redirects().follow(false).when().get("/").then().statusCode(200);

        assertEquals(0, statistics.getPrepareStatementCount(), "an anonymous GET / must issue no SQL at all");
    }
}
