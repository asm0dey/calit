package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.time.Instant;
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

    /** GoogleTokenService wired to a canned HTTP transport instead of the network. */
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
                        .setContent(jsonBody))
                .build();
    }

    @Test
    void deadRefreshTokenBecomesGoogleInvalidGrantException() {
        var svc = new TransportStubbedService(
                respondingWith(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired\"}"));

        assertThrows(
                GoogleInvalidGrantException.class, () -> svc.requestToken("refresh_token", "dead-refresh-token", NOW));
    }

    @Test
    void otherOauthErrorsCarryGoogleErrorAndDescription() {
        var svc = new TransportStubbedService(
                respondingWith(401, "{\"error\":\"invalid_client\",\"error_description\":\"Unauthorized\"}"));

        var thrown = assertThrows(
                IllegalStateException.class, () -> svc.requestToken("refresh_token", "some-refresh-token", NOW));

        assertFalse(thrown instanceof GoogleInvalidGrantException, "401 must NOT be treated as a dead grant");
        assertTrue(thrown.getMessage().contains("refresh_token"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("error=invalid_client"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("description=Unauthorized"), thrown.getMessage());
    }

    @Test
    void badRequestThatIsNotInvalidGrantIsNotTreatedAsADeadGrant() {
        var svc = new TransportStubbedService(
                respondingWith(400, "{\"error\":\"invalid_client\",\"error_description\":\"Bad client secret\"}"));

        var thrown = assertThrows(
                IllegalStateException.class, () -> svc.requestToken("refresh_token", "some-refresh-token", NOW));

        // The status alone must not condemn the grant: only 400 AND invalid_grant does.
        assertFalse(thrown instanceof GoogleInvalidGrantException, "400 alone must not mean a dead grant");
        assertTrue(thrown.getMessage().contains("error=invalid_client"), thrown.getMessage());
    }

    @Test
    void defaultTransportIsARealNetworkTransport() {
        // The seam exists only for tests; production must still get a real transport, and a fresh one
        // per call (the old inline `new NetHttpTransport()` behaviour).
        var svc = new GoogleTokenService(config());

        var first = svc.transport();

        assertInstanceOf(com.google.api.client.http.javanet.NetHttpTransport.class, first);
        assertNotSame(first, svc.transport());
    }

    @Test
    void serverErrorCarryingInvalidGrantIsStillTransient() {
        // Pins the OTHER half of the guard: a 5xx that happens to echo invalid_grant is a blip, and
        // treating it as a dead grant would permanently flag a healthy account needsReconnect.
        var svc = new TransportStubbedService(
                respondingWith(503, "{\"error\":\"invalid_grant\",\"error_description\":\"Backend error\"}"));

        var thrown = assertThrows(
                IllegalStateException.class, () -> svc.requestToken("refresh_token", "some-refresh-token", NOW));

        assertFalse(thrown instanceof GoogleInvalidGrantException, "only 400 AND invalid_grant means a dead grant");
        assertTrue(thrown.getMessage().contains("HTTP 503"), thrown.getMessage());
    }

    @Test
    void networkFailureBecomesIoErrorNotADeadGrant() {
        var transport = new MockHttpTransport.Builder()
                .setLowLevelHttpRequest(new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() throws IOException {
                        throw new IOException("connect timed out");
                    }
                })
                .build();
        var svc = new TransportStubbedService(transport);

        var thrown = assertThrows(
                IllegalStateException.class, () -> svc.requestToken("refresh_token", "some-refresh-token", NOW));

        assertFalse(thrown instanceof GoogleInvalidGrantException, "a blip must not flag the account dead");
        assertTrue(thrown.getMessage().contains("I/O error"), thrown.getMessage());
    }

    @Test
    void successfulRefreshReturnsTokenAndExpiry() {
        var svc = new TransportStubbedService(respondingWith(
                200, "{\"access_token\":\"fresh-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));

        var resp = svc.requestToken("refresh_token", "good-refresh-token", NOW);

        assertEquals("fresh-token", resp.accessToken());
        assertEquals(NOW.plusSeconds(3600), resp.expiry());
        assertNull(resp.googleSub(), "a refresh response carries no id_token claims");
    }
}
