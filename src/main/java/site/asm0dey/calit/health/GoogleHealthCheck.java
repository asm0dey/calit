package site.asm0dey.calit.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import site.asm0dey.calit.google.GoogleOAuthConfig;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Informational readiness check for Google OAuth/Calendar connectivity — always reports UP.
 * <p>
 * calit runs in degraded (Google-optional) mode, so an unreachable Google endpoint
 * must never pull a replica out of rotation. Reachability is exposed under
 * {@code data.state} (values: {@code not-configured}, {@code reachable},
 * {@code unreachable}) for operators at {@code /q/health/ready}.
 * <p>
 * ponytail: TCP reachability to oauth2.googleapis.com:443, not a real token call.
 */
@Readiness
@ApplicationScoped
public class GoogleHealthCheck implements HealthCheck {

    private static final String STATE = "state";

    @Inject
    GoogleOAuthConfig config;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder r = HealthCheckResponse.named("Google");
        String clientId = config.oauth().clientId();
        if (clientId == null || clientId.isBlank()) {
            return r.up().withData(STATE, "not-configured").build();
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("oauth2.googleapis.com", 443), 2000);
            return r.up().withData(STATE, "reachable").build();
        } catch (Exception e) {
            return r.up().withData(STATE, "unreachable").withData("error", e.getMessage()).build();
        }
    }
}
