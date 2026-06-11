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

@Path("/api/google")
public class GoogleOAuthResource {

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
        return Response.status(Response.Status.FOUND)
                .location(URI.create(tokenService.buildConsentUrl()))
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
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Google authorization failed: " + error)
                    .build();
        }
        Instant now = Instant.now();
        // Stateless CSRF check: validate the signed state with no session. /connect and /callback
        // may be served by different replicas, so verification uses only the shared signing secret.
        if (!tokenService.validateState(state, now)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid or expired OAuth state")
                    .build();
        }
        if (code == null || code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing authorization code")
                    .build();
        }
        tokenService.exchangeCode(currentOwner.id(), code, now);
        return Response.status(Response.Status.FOUND)
                .location(URI.create("/me"))
                .build();
    }
}
