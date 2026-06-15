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
 * Readiness check: can we reach the SMTP server? When the mailer is mocked
 * ({@code %dev}/{@code %test}) there is no real host, so we report UP — the app
 * must not drop out of rotation just because no SMTP is configured.
 *
 * ponytail: bare TCP connect, no SMTP handshake/auth. Add EHLO if "port open but
 * server broken" ever actually bites.
 */
@Readiness
@ApplicationScoped
public class SmtpHealthCheck implements HealthCheck {

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
            return r.up().withData("state", "mocked-or-unconfigured").build();
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host.get(), port), 2000);
            return r.up().withData("host", host.get() + ":" + port).build();
        } catch (Exception e) {
            return r.down().withData("host", host.get() + ":" + port)
                    .withData("error", e.getMessage()).build();
        }
    }
}
