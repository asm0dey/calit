package site.asm0dey.calit.web;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * The current owner's display preferences, exposed to Qute as {@code {inject:owner.*}} for the
 * /me templates. Request-scoped because it reads {@link CurrentOwner}, which {@code MeOwnerFilter}
 * populates per request (contrast {@link SiteInfo}, which is application-scoped config).
 *
 * <p>Exists so {@code adminBase.html} can carry the owner's timezone without adding a parameter to
 * every admin template's signature. Accessors never return null — Qute would render the literal
 * "null" — and the client scripts treat an empty string as "not supplied".</p>
 */
@Named("owner")
@RequestScoped
public class OwnerInfo {

    final CurrentOwner currentOwner;

    private OwnerSettings cached;

    private boolean loaded;

    @Inject
    public OwnerInfo(CurrentOwner currentOwner) {
        this.currentOwner = currentOwner;
    }

    /** Memoized so a template reading several accessors costs one query per request. */
    private OwnerSettings settings() {
        if (!loaded) {
            cached = currentOwner.isSet() ? OwnerSettings.forOwner(currentOwner.id()) : null;
            loaded = true;
        }
        return cached;
    }

    /**
     * The owner's configured IANA zone, or "" when no owner/settings row is in scope. The /me
     * pages have no timezone picker, so this is what their times are rendered in — a host who
     * travels must still read their bookings in the zone their availability is defined in.
     */
    public String getTimezone() {
        OwnerSettings s = settings();
        return (s == null || s.timezone == null) ? "" : s.timezone;
    }
}
