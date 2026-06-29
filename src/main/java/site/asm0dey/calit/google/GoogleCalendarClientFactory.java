package site.asm0dey.calit.google;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds an authorized Google Calendar service client from a bearer access token.
 * Pure wiring of Google client types; the testable behavior lives in GoogleCalendarPort
 * (mocked downstream) and GoogleTokenService (stubbed in tests).
 */
@ApplicationScoped
public class GoogleCalendarClientFactory {

    /** Scope reference kept to document the required grant; the token already carries it. */
    public static final String SCOPE = CalendarScopes.CALENDAR;

    private final GoogleOAuthConfig config;

    @Inject
    public GoogleCalendarClientFactory(GoogleOAuthConfig config) {
        this.config = config;
    }

    /** Build a Calendar service authorized with the given bearer access token. */
    public Calendar build(String accessToken) {
        Credential credential =
                new Credential(BearerToken.authorizationHeaderAccessMethod()).setAccessToken(accessToken);
        // SEC-SSRF-01: wrap the credential initializer so every outbound Calendar request gets bounded
        // connect/read timeouts (a hung Google upstream can't otherwise pin a thread). Fixed destination
        // (no SSRF) — availability hardening. Still runs the credential initializer (auth header).
        HttpRequestInitializer withTimeouts = request -> {
            credential.initialize(request);
            request.setConnectTimeout(5000); // ms
            request.setReadTimeout(10000); // ms
        };
        return new Calendar.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), withTimeouts)
                .setApplicationName(config.applicationName())
                .build();
    }
}
