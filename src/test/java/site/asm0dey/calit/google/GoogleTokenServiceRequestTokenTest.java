package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Drives the REAL requestToken body (which every other test stubs out) against an in-memory
 * transport, so Google's error payloads are mapped by production code, not by a stub.
 */
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
        when(oauth.redirectUri()).thenReturn("https://book.example.com/api/google/callback");
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
