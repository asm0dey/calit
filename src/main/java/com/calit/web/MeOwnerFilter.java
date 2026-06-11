package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.CurrentOwner;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

/**
 * Resolves the request's owner for /me and /me/* from the authenticated SecurityIdentity principal
 * and stashes it in {@link CurrentOwner}. Security (the `user` role requirement) is enforced
 * separately by the HTTP permission policy + @RolesAllowed; by the time this filter runs the
 * identity is already authenticated. Phase 4 adds the first-login wizard redirect here.
 */
@Provider
public class MeOwnerFilter implements ContainerRequestFilter {

    @Inject
    SecurityIdentity identity;

    @Inject
    CurrentOwner currentOwner;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!matchesMe(ctx.getUriInfo())) {
            return;
        }
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return; // unauthenticated; the permission policy already rejects/redirects this request
        }
        AppUser user = AppUser.findByUsername(identity.getPrincipal().getName());
        if (user != null) {
            currentOwner.set(user);
        }
    }

    /** True for /me, /me/*, /api/google, and /api/google/* (all owner-scoped, authenticated routes). */
    private static boolean matchesMe(UriInfo uriInfo) {
        String path = uriInfo.getPath(); // no leading slash, e.g. "me" or "me/settings"
        return path.equals("me") || path.startsWith("me/")
                || path.equals("api/google") || path.startsWith("api/google/");
    }
}
