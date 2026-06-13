package com.calit.google;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.Instant;

@io.quarkus.security.Authenticated
@Path("/api/google")
public class GoogleOAuthResource {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(GoogleOAuthResource.class);

    private final GoogleTokenService tokenService;
    private final com.calit.user.CurrentOwner currentOwner;

    @Inject
    public GoogleOAuthResource(GoogleTokenService tokenService,
                               com.calit.user.CurrentOwner currentOwner) {
        this.tokenService = tokenService;
        this.currentOwner = currentOwner;
    }

    /** Kick off the owner consent flow: 302 to Google. */
    @GET
    @Path("/connect")
    public Response connect() {
        long ownerId = currentOwner.id(); // @Authenticated guarantees a principal, so this won't NPE-on-unbox
        return Response.status(Response.Status.FOUND)
                .location(URI.create(tokenService.buildConsentUrl(ownerId, Instant.now())))
                .build();
    }

    /** Google redirects back here with ?code=...&state=... (or ?error=...). */
    @GET
    @Path("/callback")
    @Produces(MediaType.TEXT_PLAIN)
    public Response callback(@QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String error) {
        if (error != null) {
            // Do not reflect attacker-controlled ?error= into the response (SEC-INPUT-03).
            LOG.warnf("Google OAuth callback returned error: %s",
                    error.replace('\r', ' ').replace('\n', ' '));
            return Response.status(Response.Status.BAD_REQUEST)
                    .header("X-Content-Type-Options", "nosniff")
                    .entity("Google authorization failed. Please try connecting again.")
                    .build();
        }
        Instant now = Instant.now();
        // The owner is recovered from the trusted, HMAC-signed state — NOT from CurrentOwner —
        // because Google's redirect may arrive without the session. /connect and /callback may
        // be served by different replicas, so verification uses only the shared signing secret.
        Long ownerId = tokenService.validateState(state, now);
        if (ownerId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid or expired OAuth state")
                    .build();
        }
        if (code == null || code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing authorization code")
                    .build();
        }
        tokenService.exchangeCode(ownerId, code, now);
        return Response.status(Response.Status.FOUND)
                .location(URI.create("/me/google"))
                .build();
    }
}
