package site.asm0dey.calit.test;

import module java.base;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The test database, in place of Dev Services. Dev Services stops its container at shutdown even with
 * {@code reuse=true}, and Testcontainers only reuses a running container, so every run and every
 * {@code @TestProfile} app paid for a fresh Postgres. This one is never stopped: with {@code
 * testcontainers.reuse.enable=true} in {@code ~/.testcontainers.properties} the next run attaches to it;
 * without it (CI), Ryuk removes it when the JVM exits. {@code quarkus.flyway.clean-at-start} makes a
 * container left behind by another branch harmless.
 */
@WithTestResource(value = PostgresResource.class, scope = TestResourceScope.GLOBAL)
public class PostgresResource implements QuarkusTestResourceLifecycleManager {
    private static final String DB = "calit";
    /**
     * Each Quarkus test app loads this class in its own classloader, so a static field would start a
     * container per app. A system property is JVM-wide: the first app starts the container and every
     * later app in the fork reuses its URL.
     */
    private static final String URL_PROPERTY = "calit.test-db.url";

    @Override
    public synchronized Map<String, String> start() {
        var url = System.getProperty(URL_PROPERTY);
        if (url == null) {
            var postgres = new GenericContainer<>("postgres:18")
                .withEnv(Map.of("POSTGRES_DB", DB, "POSTGRES_USER", DB, "POSTGRES_PASSWORD", DB))
                // test data is thrown away; durability only costs time
                .withCommand("postgres", "-c", "fsync=off")
                .withExposedPorts(5432)
                // CLAUDE.md: `docker rm -f $(docker ps -q --filter label=calit.test-db)` for a fresh one
                .withLabel("calit.test-db", "true")
                .withReuse(true)
                // the image's init script runs a temporary server first, so the second line is the real one
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
            postgres.start();
            url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + DB;
            System.setProperty(URL_PROPERTY, url);
        }
        return Map.of(
                "quarkus.datasource.jdbc.url",
                url,
                "quarkus.datasource.username",
                DB,
                "quarkus.datasource.password",
                DB
        );
    }

    @Override
    public void stop() {
        // never stopped: see the class javadoc
    }
}
