package site.asm0dey.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import site.asm0dey.calit.booking.events.HostConsentRequested;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

/**
 * Host-set logic for multi-host meeting types: membership, bookability gate, organizer choice,
 * per-host buffers, co-host eligibility. Single-host types short-circuit to the creator alone.
 */
@ApplicationScoped
public class MeetingHosts {

    public static final int MAX_HOSTS = 10;

    private final CalendarPort calendarPort;

    @Inject
    Event<HostConsentRequested> consentEvent;

    @Inject
    public MeetingHosts(CalendarPort calendarPort) {
        this.calendarPort = calendarPort;
    }

    /** Owner ids that must all be free: [creator] for single-host; creator + accepted co-hosts otherwise. */
    public List<Long> hostOwnerIds(MeetingType type) {
        if (!MeetingTypeHost.isMultiHost(type.id)) {
            return List.of(type.ownerId);
        }
        List<Long> ids = new ArrayList<>();
        for (MeetingTypeHost h : MeetingTypeHost.acceptedForType(type.id)) {
            ids.add(h.ownerId);
        }
        return ids;
    }

    /** Single-host is always bookable. Multi-host requires every host ACCEPTED and every host enabled. */
    public boolean bookable(MeetingType type) {
        if (!MeetingTypeHost.isMultiHost(type.id)) {
            return true;
        }
        List<MeetingTypeHost> hosts = MeetingTypeHost.forType(type.id);
        for (MeetingTypeHost h : hosts) {
            if (!h.accepted()) {
                return false;
            }
            AppUser u = AppUser.findById(h.ownerId);
            if (u == null || !u.enabled) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bulk {@link #bookable(MeetingType)} over a set of types in two queries total (all host rows +
     * all host users) instead of per-type {@code forType} + per-host {@code findById}. Returns the
     * ids of the types that ARE bookable: a single-host type (no COHOST row) is always bookable; a
     * multi-host type only when EVERY host row is ACCEPTED and its user exists and is enabled.
     */
    public Set<Long> bookableTypeIds(Collection<MeetingType> types) {
        if (types.isEmpty()) {
            return Set.of();
        }
        List<Long> typeIds = types.stream().map(t -> t.id).toList();
        Map<Long, List<MeetingTypeHost>> hostsByType =
                MeetingTypeHost.<MeetingTypeHost>list("meetingTypeId in ?1", typeIds).stream()
                        .collect(Collectors.groupingBy(h -> h.meetingTypeId));
        Set<Long> ownerIds = hostsByType.values().stream()
                .flatMap(List::stream)
                .map(h -> h.ownerId)
                .collect(Collectors.toSet());
        Map<Long, Boolean> enabledById = ownerIds.isEmpty()
                ? Map.of()
                : AppUser.<AppUser>list("id in ?1", ownerIds).stream()
                        .collect(Collectors.toMap(u -> u.id, u -> u.enabled));
        Set<Long> bookable = new HashSet<>();
        for (MeetingType t : types) {
            List<MeetingTypeHost> hosts = hostsByType.get(t.id);
            var multiHost = hosts != null && hosts.stream().anyMatch(h -> MeetingTypeHost.COHOST.equals(h.role));
            if (!multiHost) {
                bookable.add(t.id); // single-host: mirrors bookable()'s early `return true`
                continue;
            }
            boolean allOk =
                    hosts.stream().allMatch(h -> h.accepted() && Boolean.TRUE.equals(enabledById.get(h.ownerId)));
            if (allOk) {
                bookable.add(t.id);
            }
        }
        return bookable;
    }

    /** Creator if connected, else the lowest-id connected host, else null (no Google event). */
    public Long chooseOrganizer(MeetingType type, List<Long> hostOwnerIds) {
        if (calendarPort.isConnected(type.ownerId)) {
            return type.ownerId;
        }
        Long chosen = null;
        for (Long id : hostOwnerIds) {
            if (calendarPort.isConnected(id) && (chosen == null || id < chosen)) {
                chosen = id;
            }
        }
        return chosen;
    }

    public int effectiveBufferBefore(MeetingType type, Long hostOwnerId) {
        MeetingTypeHost h = MeetingTypeHost.find(type.id, hostOwnerId);
        return (h != null && h.bufferBeforeMinutes != null) ? h.bufferBeforeMinutes : type.bufferBeforeMinutes;
    }

    public int effectiveBufferAfter(MeetingType type, Long hostOwnerId) {
        MeetingTypeHost h = MeetingTypeHost.find(type.id, hostOwnerId);
        return (h != null && h.bufferAfterMinutes != null) ? h.bufferAfterMinutes : type.bufferAfterMinutes;
    }

    public boolean eligibleCohost(Long meetingTypeId, Long creatorOwnerId, AppUser candidate) {
        if (candidate == null || !candidate.enabled || !candidate.settingsComplete) {
            return false;
        }
        if (candidate.id.equals(creatorOwnerId)) {
            return false;
        }
        return MeetingTypeHost.find(meetingTypeId, candidate.id) == null;
    }

    /**
     * Ensures the CREATOR row exists, then inserts a PENDING co-host row and fires a consent
     * request. Idempotent: a candidate already present (any status) is a no-op — no duplicate
     * row, no repeat email.
     */
    @Transactional
    public void addCohost(MeetingType type, AppUser candidate) {
        MeetingTypeHost existing = MeetingTypeHost.find(type.id, candidate.id);
        if (existing != null) {
            return; // idempotent: already a host (pending or accepted) -> no-op, no repeat email
        }
        ensureCreatorRow(type);
        long hostCount = MeetingTypeHost.count("meetingTypeId", type.id);
        if (hostCount >= MAX_HOSTS) {
            throw new HostRuleException("adm_hosts_error_cap", MAX_HOSTS);
        }
        // ponytail: constant cap, revisit only if a real use-case needs more
        assertSlugFreeForCohost(type, candidate);
        MeetingTypeHost h = MeetingTypeHost.of(type.id, candidate.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
        h.consentToken = UUID.randomUUID();
        h.persist();
        consentEvent.fire(new HostConsentRequested(type.id, candidate.id, h.consentToken.toString()));
    }

    /**
     * A candidate must not already own, or co-host, another type with the same slug as {@code
     * type} — their public URL would collide once they accept.
     */
    public void assertSlugFreeForCohost(MeetingType type, AppUser candidate) {
        if (MeetingType.slugUsedByOwner(candidate.id, type.slug, null)) {
            throw new HostRuleException("adm_hosts_error_slug_owned", candidate.username, type.slug);
        }
        // also reject if candidate is a host of another multi-host type with this slug
        for (MeetingTypeHost h : MeetingTypeHost.cohostedTypesFor(candidate.id)) {
            MeetingType other = MeetingType.findById(h.meetingTypeId);
            if (other != null && !other.id.equals(type.id) && other.slug.equals(type.slug)) {
                throw new HostRuleException("adm_hosts_error_slug_cohosts", candidate.username, type.slug);
            }
        }
    }

    /** For create/rename of a shared type: slug must be free in every host's namespace. */
    public void assertSlugFreeAcrossHosts(MeetingType type, String newSlug) {
        for (Long hostId : hostOwnerIds(type)) {
            if (!hostId.equals(type.ownerId) && MeetingType.slugUsedByOwner(hostId, newSlug, null)) {
                throw new HostRuleException("adm_hosts_error_slug_across", newSlug);
            }
        }
    }

    private void ensureCreatorRow(MeetingType type) {
        if (MeetingTypeHost.find(type.id, type.ownerId) == null) {
            MeetingTypeHost.of(type.id, type.ownerId, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                    .persist();
        }
    }

    /** Marks a pending co-host row accepted and clears its one-time consent token. */
    @Transactional
    public void acceptConsent(MeetingTypeHost host) {
        host.status = MeetingTypeHost.ACCEPTED;
        host.respondedAt = Instant.now();
        host.consentToken = null;
        host.persist();
    }

    /**
     * Task 18: how many of {@code hostOwnerId}'s own rows in future (PENDING|CONFIRMED) group
     * bookings of {@code type} exist right now. A non-zero count is what triggers the
     * keep-vs-cancel interstitial in {@link site.asm0dey.calit.web.AdminResource#removeCohost}
     * instead of removing the host immediately. Standalone (non-group) bookings are irrelevant
     * here — a host row is only ever a group row.
     */
    public long countFutureBookings(MeetingType type, Long hostOwnerId) {
        return Booking.count(
                "ownerId = ?1 and meetingTypeId = ?2 and startUtc > ?3 and status in ?4 and groupId is not null",
                hostOwnerId,
                type.id,
                Instant.now(),
                List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED));
    }

    /**
     * Removes a co-host; if no co-hosts remain, also removes the CREATOR row (revert to
     * single-host). Defense-in-depth guard against removing the CREATOR row directly (Task 17
     * review): {@link site.asm0dey.calit.web.AdminResource#removeCohost} already rejects this
     * before calling here (with a localized alert), so this exception is only reachable via a
     * caller that skips that check.
     */
    @Transactional
    public void removeHost(MeetingType type, Long cohostOwnerId) {
        if (cohostOwnerId.equals(type.ownerId)) {
            throw new IllegalStateException("The creator cannot be removed from their own meeting type.");
        }
        MeetingTypeHost.delete("meetingTypeId = ?1 and ownerId = ?2", type.id, cohostOwnerId);
        if (MeetingTypeHost.count("meetingTypeId = ?1 and role = ?2", type.id, MeetingTypeHost.COHOST) == 0) {
            MeetingTypeHost.delete("meetingTypeId = ?1 and role = ?2", type.id, MeetingTypeHost.CREATOR);
        }
    }
}
