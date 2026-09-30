package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Drives the REAL requestToken body (which every other test stubs out) against an in-memory
 * transport, so Google's error payloads are mapped by production code, not by a stub.
 *
 * <p>@QuarkusTest is required for the coverage to count: quarkus-jacoco only records what executes
 * inside the Quarkus test run, so a plain JUnit class would pass while reporting zero coverage.
 */
@QuarkusTest
class GoogleTokenServiceRequestTokenTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * GoogleTokenService wired to a canned HTTP transport instead of the network.
     */
    static class TransportStubbedService extends GoogleTokenService {
        private final HttpTransport transport;

        TransportStubbedService(HttpTransport transport) {
            super(config());
            this.transport = transport;
        }

        @Override
        protected HttpTransport transport() {
            return transport;
        }
    }

    private static GoogleOAuthConfig config() {
        var oauth = mock(GoogleOAuthConfig.OAuth.class);
        when(oauth.clientId()).thenReturn("test-client-id");
        when(oauth.clientSecret()).thenReturn("test-client-secret");
        var config = mock(GoogleOAuthConfig.class);
        when(config.oauth()).thenReturn(oauth);
        return config;
    }

    private static HttpTransport respondingWith(int status, String jsonBody) {
        return new MockHttpTransport.Builder()
            .setLowLevelHttpResponse(new MockLowLevelHttpResponse()
                .setStatusCode(status)
                .setContentType("application/json")
                .setContent(jsonBody)
            )
            .build();
    }

    @Test
    void deadRefreshTokenBecomesGoogleInvalidGrantException() {
        var svc = new TransportStubbedService(
                respondingWith(
                        400,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"Token has " + "been expired\"}"
                )
        );

        assertThatExceptionOfType(GoogleInvalidGrantException.class)
            .isThrownBy(() -> svc.requestToken("refresh_token", "dead-refresh-token", NOW));
    }

    @Test
    void otherOauthErrorsCarryGoogleErrorAndDescription() {
        var svc = new TransportStubbedService(
                respondingWith(401, "{\"error\":\"invalid_client\",\"error_description\":" + "\"Unauthorized\"}")
        );

        var thrown = assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> svc.requestToken("refresh_token", "some-refresh-token", NOW))
            .actual();

        assertThat(thrown).as("401 must NOT be treated as a dead grant").isNotInstanceOf(
                GoogleInvalidGrantException.class
        );
        assertThat(thrown.getMessage()).as(thrown.getMessage()).contains("refresh_token");
        assertThat(thrown.getMessage()).as(thrown.getMessage()).contains("error=invalid_client");
        assertThat(thrown.getMessage()).as(thrown.getMessage()).contains("description=Unauthorized");
    }

    @Test
    void badRequestThatIsNotInvalidGrantIsNotTreatedAsADeadGrant() {
        var svc = new TransportStubbedService(
                respondingWith(400, "{\"error\":\"invalid_client\",\"error_description\":\"Bad client " + "secret\"}")
        );

        var thrown = assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> svc.requestToken("refresh_token", "some-refresh-token", NOW))
            .actual();
        // The status alone must not condemn the grant: only 400 AND invalid_grant does.
        assertThat(thrown).as("400 alone must not mean a dead grant").isNotInstanceOf(GoogleInvalidGrantException.class);
        assertThat(thrown.getMessage()).as(thrown.getMessage()).contains("error=invalid_client");
    }

    @Test
    void defaultTransportIsARealNetworkTransport() {
        // The seam exists only for tests; production must still get a real transport, and a fresh one
        // per call (the old inline `new NetHttpTransport()` behaviour).
        var svc = new GoogleTokenService(config());

        var first = svc.transport();

        assertThat(first).isInstanceOf(com.google.api.client.http.javanet.NetHttpTransport.class);
        assertThat(svc.transport()).isNotSameAs(first);
    }

    @Test
    void serverErrorCarryingInvalidGrantIsStillTransient() {
        // Pins the OTHER half of the guard: a 5xx that happens to echo invalid_grant is a blip, and
        // treating it as a dead grant would permanently flag a healthy account needsReconnect.
        var svc = new TransportStubbedService(
                respondingWith(503, "{\"error\":\"invalid_grant\",\"error_description\":\"Backend " + "error\"}")
        );

        var thrown = assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> svc.requestToken("refresh_token", "some-refresh-token", NOW))
            .actual();

        assertThat(thrown)
            .as("only 400 AND invalid_grant means a dead grant")
            .isNotInstanceOf(GoogleInvalidGrantException.class);
        assertThat(thrown.getMessage()).as(thrown.getMessage()).contains("HTTP 503");
    }

    @Test
    void networkFailureBecomesIoErrorNotADeadGrant() {
        var transport =
                new MockHttpTransport.Builder().setLowLevelHttpRequest(new MockLowLevelHttpRequest() {
            @Override
            public LowLevelHttpResponse execute() throws IOException {
                throw new IOException("connect timed out");
            }
        }).build();
        var svc = new TransportStubbedService(transport);

        var thrown = assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> svc.requestToken("refresh_token", "some-refresh-token", NOW))
            .actual();

        assertThat(thrown).as("a blip must not flag the account dead").isNotInstanceOf(
                GoogleInvalidGrantException.class
        );
        assertThat(thrown.getMessage()).as(thrown.getMessage()).contains("I/O error");
    }

    @Test
    void successfulRefreshReturnsTokenAndExpiry() {
        var svc = new TransportStubbedService(
                respondingWith(
                        200,
                        "{\"access_token\":\"fresh-token\",\"expires_in\":3600,\"token_" + "type\":\"Bearer\"}"
                )
        );

        var resp = svc.requestToken("refresh_token", "good-refresh-token", NOW);

        assertThat(resp.accessToken()).isEqualTo("fresh-token");
        assertThat(resp.expiry()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(resp.googleSub()).as("a refresh response carries no id_token claims").isNull();
    }
}
