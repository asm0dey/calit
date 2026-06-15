package com.calit.health;

import com.calit.google.GoogleOAuthConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Readiness check: can we reach Google's OAuth/token endpoint? calit runs in
 * degraded (Google-optional) mode, so when no client-id is configured we report
 * UP — gating readiness on an unconfigured integration would mark every replica
 * permanently DOWN.
 *
 * ponytail: TCP reachability to oauth2.googleapis.com:443, not a real token call.
 */
@Readiness
@ApplicationScoped
public class GoogleHealthCheck implements HealthCheck {

    @Inject
    GoogleOAuthConfig config;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder r = HealthCheckResponse.named("Google");
        String clientId = config.oauth().clientId();
        if (clientId == null || clientId.isBlank()) {
            return r.up().withData("state", "not-configured").build();
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("oauth2.googleapis.com", 443), 2000);
            return r.up().build();
        } catch (Exception e) {
            return r.down().withData("error", e.getMessage()).build();
        }
    }
}
