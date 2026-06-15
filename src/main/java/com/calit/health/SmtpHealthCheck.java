package com.calit.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;

/**
 * Informational readiness check for the SMTP server — always reports UP.
 * <p>
 * Now that the transactional outbox queues mail while SMTP is unreachable, a
 * down mail server must not pull a replica out of rotation. Reachability is
 * exposed under {@code data.state} (values: {@code mocked-or-unconfigured},
 * {@code reachable}, {@code unreachable}) for operators at {@code /q/health/ready}.
 * <p>
 * ponytail: bare TCP connect, no SMTP handshake/auth. Add EHLO if "port open but
 * server broken" ever actually bites.
 */
@Readiness
@ApplicationScoped
public class SmtpHealthCheck implements HealthCheck {

    private static final String STATE = "state";

    @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false")
    boolean mock;

    @ConfigProperty(name = "quarkus.mailer.host")
    Optional<String> host;

    @ConfigProperty(name = "quarkus.mailer.port", defaultValue = "587")
    int port;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder r = HealthCheckResponse.named("SMTP");
        if (mock || host.isEmpty()) {
            return r.up().withData(STATE, "mocked-or-unconfigured").build();
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host.get(), port), 2000);
            return r.up().withData(STATE, "reachable").withData("host", host.get() + ":" + port).build();
        } catch (Exception e) {
            // UP, not DOWN: the outbox queues mail while SMTP is down -- don't drop out of rotation.
            return r.up().withData(STATE, "unreachable")
                    .withData("host", host.get() + ":" + port)
                    .withData("error", e.getMessage()).build();
        }
    }
}
