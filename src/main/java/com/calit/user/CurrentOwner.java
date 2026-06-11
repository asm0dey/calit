package com.calit.user;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Request-scoped holder for the AppUser that owns the current request's data. Set by
 * {@code MeOwnerFilter} for /me and /me/* (Phase 3 will also set it for public /{user}/* routes).
 * Owner-scoped queries read {@link #id()} to filter their results.
 */
@RequestScoped
public class CurrentOwner {

    private AppUser owner;

    public void set(AppUser owner) {
        this.owner = owner;
    }

    public AppUser get() {
        return owner;
    }

    public boolean isSet() {
        return owner != null;
    }

    /** The owner's id, or null when unset. */
    public Long id() {
        return owner == null ? null : owner.id;
    }

    /** The owner, or a 401 WebApplicationException when no owner has been resolved. */
    public AppUser require() {
        if (owner == null) {
            throw new WebApplicationException("No owner in request scope", Response.Status.UNAUTHORIZED);
        }
        return owner;
    }
}
