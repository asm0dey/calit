package site.asm0dey.calit.caldav;

import module java.base;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * A throwaway Radicale CalDAV server with one user, {@code alice:secret}. Exposes {@code caldav.test.url}
 * and lets the client reach it on localhost.
 */
public class RadicaleResource implements QuarkusTestResourceLifecycleManager {
    private static final String CONFIG =
            """
            [server]
            hosts = 0.0.0.0:5232
            [auth]
            type = htpasswd
            htpasswd_filename = /config/users
            htpasswd_encryption = plain
            [rights]
            type = owner_only
            [storage]
            filesystem_folder = /data/collections
            """;
    private GenericContainer<?> radicale;

    @Override
    public Map<String, String> start() {
        radicale = new GenericContainer<>("tomsquest/docker-radicale:3.8.3.0")
            .withExposedPorts(5232)
            .withCopyToContainer(Transferable.of(CONFIG), "/config/config")
            .withCopyToContainer(Transferable.of("alice:secret\n"), "/config/users")
            .waitingFor(Wait.forHttp("/").forStatusCode(401).forStatusCode(200).forStatusCode(302));
        radicale.start();
        return Map.of(
                "caldav.test.url",
                "http://" + radicale.getHost() + ":" + radicale.getMappedPort(5232) + "/",
                "calit.caldav.allow-private-hosts",
                "true"
        );
    }

    @Override
    public void stop() {
        if (radicale != null) {
            radicale.stop();
        }
    }
}
