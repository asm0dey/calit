package site.asm0dey.calit.web;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import site.asm0dey.calit.booking.MeetingHosts;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * Progressive-enhancement co-host typeahead (Task 20). The plain {@code <input name=cohost>} in
 * {@code _hostlist.html} already works without JS (server validates via {@link
 * MeetingHosts#eligibleCohost} on submit, Task 6/17) — this endpoint only feeds the
 * {@code <datalist>} suggestions for browsers with JS enabled.
 */
@Path("/me/hosts")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class HostSuggestResource {

    private static final int MAX_SUGGESTIONS = 20;

    private final CurrentOwner currentOwner;

    @Inject
    public HostSuggestResource(CurrentOwner currentOwner) {
        this.currentOwner = currentOwner;
    }

    public record Suggestion(String username) {}

    /** Up to {@value #MAX_SUGGESTIONS} usernames starting with {@code q}, eligible to co-host {@code typeId}. */
    @GET
    public List<Suggestion> suggest(@QueryParam("q") String q, @QueryParam("typeId") Long typeId) {
        List<Suggestion> results = new ArrayList<>();
        if (q == null || q.isBlank()) {
            return results; // guard: don't scan all users for an empty prefix
        }
        // Tenant gate: eligibleCohost() only excludes existing hosts, so calling it for a typeId
        // the caller doesn't own would leak cross-tenant host membership (Task 20 review). Mirror
        // AdminResource#requireType's ownership check, but return an empty suggestion list instead
        // of a 404 - this is a typeahead, not a resource lookup.
        MeetingType type = typeId == null ? null : MeetingType.findById(typeId);
        if (type == null || !type.ownerId.equals(currentOwner.id())) {
            return results;
        }
        List<AppUser> candidates = AppUser.list(
                "enabled = true and settingsComplete = true and lower(username) like ?1 escape '\\' order by username",
                escapeLike(q.toLowerCase()) + "%");
        // Preload the type's existing host ownerIds ONCE (was MeetingTypeHost.find per candidate via
        // eligibleCohost). Candidates are already enabled + settingsComplete via the query; the
        // remaining eligibleCohost checks (not the creator, not already a host of any status) run
        // in-memory here — kept in sync with MeetingHosts#eligibleCohost.
        Set<Long> existingHostIds =
                MeetingTypeHost.forType(typeId).stream().map(h -> h.ownerId).collect(Collectors.toSet());
        Long creatorId = currentOwner.id();
        for (AppUser candidate : candidates) {
            if (results.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (!candidate.id.equals(creatorId) && !existingHostIds.contains(candidate.id)) {
                results.add(new Suggestion(candidate.username));
            }
        }
        return results;
    }

    /** Backslash-escapes {@code \}, {@code %}, {@code _} so user input stays a literal LIKE prefix. */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
