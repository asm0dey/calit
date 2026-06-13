package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.CurrentOwner;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

/**
 * Resolves the request's owner for /me, /me/* and /api/google* from the authenticated
 * SecurityIdentity principal and stashes it in {@link CurrentOwner}. Security (the `user` role
 * requirement) is enforced separately by the HTTP permission policy + @RolesAllowed; by the time
 * this filter runs the identity is already authenticated. Phase 4 also forces the first-login
 * wizard: an authenticated but not-yet-onboarded user is 302'd to /me/setup for /me UI requests
 * (OAuth /api/google* and the wizard page itself are exempt).
 */
@Provider
public class MeOwnerFilter implements ContainerRequestFilter {

    @Inject
    SecurityIdentity identity;

    @Inject
    CurrentOwner currentOwner;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = normalizePath(ctx.getUriInfo().getPath());
        if (!isOwnerScoped(path)) {
            return; // not an owner-scoped route
        }
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return; // unauthenticated; the permission policy already handles this
        }
        AppUser user = AppUser.findByUsername(identity.getPrincipal().getName());
        if (user == null) {
            // Authenticated principal with no backing AppUser row — fail closed (SEC-AUTHZ-03)
            // rather than relying on downstream null-handling. Upstream augmentor makes this
            // near-unreachable, so this is defense-in-depth.
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
            return;
        }
        currentOwner.set(user); // Phase 2 owner resolution — covers /me* AND /api/google*

        // Phase 4: force the first-login wizard until onboarding completes — for the /me UI only.
        // NOT /api/google (OAuth must not be redirected) and NOT the wizard page itself (no self-loop).
        boolean onboarded = !user.mustChangePassword && user.settingsComplete;
        if (isMeUiPath(path) && !isSetupPath(path) && !onboarded) {
            ctx.abortWith(Response.status(Response.Status.FOUND) // 302
                    .location(UriBuilder.fromUri("/me/setup").build())
                    .build());
        }
    }

    /** Path without a leading slash (the runtime may report it either way). */
    private static String normalizePath(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /** Routes whose owner must be resolved: the /me UI AND the /api/google integration. */
    private static boolean isOwnerScoped(String path) {
        return isMeUiPath(path) || path.equals("api/google") || path.startsWith("api/google/");
    }

    private static boolean isMeUiPath(String path) {
        return path.equals("me") || path.startsWith("me/");
    }

    /** The wizard page must stay reachable while onboarding is incomplete (no self-redirect). */
    private static boolean isSetupPath(String path) {
        return path.equals("me/setup");
    }
}
