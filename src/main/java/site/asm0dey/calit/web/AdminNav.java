package site.asm0dey.calit.web;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * Per-request admin-sidebar nav state exposed to Qute as {@code {inject:nav.*}} (mirrors the
 * {@code {inject:site.*}} pattern in {@link SiteInfo}). Reads the already request-scoped {@link
 * CurrentOwner}, so {@code adminBase.html} — included by ~12 admin templates — can resolve {@code
 * hasShared} on every admin page without a new param threaded through every {@code
 * @CheckedTemplate} method and call site.
 */
@Named("nav")
@RequestScoped
public class AdminNav {
    final CurrentOwner currentOwner;

    @Inject
    public AdminNav(CurrentOwner currentOwner) {
        this.currentOwner = currentOwner;
    }

    /**
     * True when the current owner is involved — in ANY role (creator or co-host) and ANY status
     * (accepted or pending) — in at least one multi-host meeting type. Deliberately unfiltered by
     * role/status so a co-host with only a PENDING consent invite still sees the sidebar "Shared"
     * link to find {@code /me/shared/requests} and accept it.
     */
    public boolean getHasShared() {
        return currentOwner.isSet() && MeetingTypeHost.count("ownerId", currentOwner.id()) > 0;
    }
}
