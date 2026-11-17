package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.HostConsentRequested;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.MeetingTypeDurationRepository;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.domain.MeetingTypeHostRepository;
import site.asm0dey.calit.domain.MeetingTypeRepository;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.AppUserRepository;

/**
 * {@link MeetingHosts} decisions over mocked repositories: no database (#343).
 */
class MeetingHostsWiringTest {
    private static final Long OWNER = 1L;
    private static final Long TYPE_ID = 7L;
    private final CalendarPort calendarPort = mock(CalendarPort.class);
    @SuppressWarnings("unchecked")
    private final Event<HostConsentRequested> consentEvent = mock(Event.class);
    private final MeetingTypeRepository meetingTypes = mock(MeetingTypeRepository.class);
    private final MeetingTypeHostRepository hosts = mock(MeetingTypeHostRepository.class);
    private final MeetingTypeDurationRepository durations = mock(MeetingTypeDurationRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final MeetingHosts meetingHosts =
            new MeetingHosts(calendarPort, consentEvent, meetingTypes, hosts, durations, bookings, users);

    private static MeetingType type(int bufferBefore, int bufferAfter) {
        var t = new MeetingType();
        t.id = TYPE_ID;
        t.ownerId = OWNER;
        t.slug = "intro";
        t.durationMinutes = 30;
        t.bufferBeforeMinutes = bufferBefore;
        t.bufferAfterMinutes = bufferAfter;
        return t;
    }

    private static AppUser user(long id, String username) {
        var u = new AppUser();
        u.id = id;
        u.username = username;
        u.enabled = true;
        u.settingsComplete = true;
        return u;
    }

    private MeetingTypeHost hostRow(Long ownerId, String role, Integer bufferOverride) {
        var h = MeetingTypeHost.of(TYPE_ID, ownerId, role, MeetingTypeHost.ACCEPTED);
        h.bufferBeforeMinutes = bufferOverride;
        h.bufferAfterMinutes = bufferOverride;
        when(hosts.findHost(TYPE_ID, ownerId)).thenReturn(h);
        return h;
    }

    /**
     * The type buffers are 10/10; the 120-minute length carries {@code durationOverride}.
     */
    private MeetingType bufferSeed(Integer hostOverride, Integer durationOverride) {
        hostRow(OWNER, MeetingTypeHost.CREATOR, hostOverride);
        var d = new MeetingTypeDuration();
        d.meetingTypeId = TYPE_ID;
        d.durationMinutes = 120;
        d.bufferBeforeMinutes = durationOverride;
        d.bufferAfterMinutes = durationOverride;
        when(durations.findRow(TYPE_ID, 120)).thenReturn(d);
        return type(10, 10);
    }

    @Test
    void creatorConnectedIsOrganizerButCreatorDisconnectedMeansNoOrganizer() {
        MeetingType t = type(5, 10);
        var creatorRow = hostRow(OWNER, MeetingTypeHost.CREATOR, null);
        var cohostRow = hostRow(2L, MeetingTypeHost.COHOST, null);
        when(hosts.isMultiHost(TYPE_ID)).thenReturn(true);
        when(hosts.acceptedForType(TYPE_ID)).thenReturn(List.of(creatorRow, cohostRow));
        List<Long> hostIds = meetingHosts.hostOwnerIds(t);
        assertThat(hostIds).hasSize(2);
        var cohostId = hostIds
            .stream()
            .filter(id -> !id.equals(1L))
            .findFirst()
            .orElseThrow();

        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(cohostId)).thenReturn(false);
        assertThat(meetingHosts.chooseOrganizer(t)).isOne();
        // Creator disconnected: a connected Co-host is never promoted.
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        assertThat(meetingHosts.chooseOrganizer(t)).isNull();
    }

    @Test
    void perHostBufferOverridesType() {
        MeetingType t = type(5, 10);
        MeetingTypeHost creator = hostRow(OWNER, MeetingTypeHost.CREATOR, null);
        // override
        creator.bufferBeforeMinutes = 20;
        // overridden
        assertThat(meetingHosts.effectiveBufferBefore(t, 1L, t.durationMinutes)).isEqualTo(20);
        // inherits type
        assertThat(meetingHosts.effectiveBufferAfter(t, 1L, t.durationMinutes)).isEqualTo(10);
    }

    @Test
    void eligibleCohostCoversEveryBranch() {
        Long creatorOwnerId = 1L;

        AppUser disabled = user(2L, "disabled-user");
        disabled.enabled = false;
        assertThat(meetingHosts.eligibleCohost(TYPE_ID, creatorOwnerId, disabled)).isFalse();

        AppUser incomplete = user(3L, "incomplete-user");
        incomplete.settingsComplete = false;
        assertThat(meetingHosts.eligibleCohost(TYPE_ID, creatorOwnerId, incomplete)).isFalse();

        AppUser creator = user(creatorOwnerId, "admin");
        assertThat(meetingHosts.eligibleCohost(TYPE_ID, creatorOwnerId, creator)).isFalse();

        AppUser alreadyHost = user(4L, "already-host");
        hostRow(alreadyHost.id, MeetingTypeHost.COHOST, null).status = MeetingTypeHost.PENDING;
        assertThat(meetingHosts.eligibleCohost(TYPE_ID, creatorOwnerId, alreadyHost)).isFalse();

        AppUser fresh = user(5L, "fresh-candidate");
        assertThat(meetingHosts.eligibleCohost(TYPE_ID, creatorOwnerId, fresh)).isTrue();
    }

    @Test
    void capRejectsEleventhHost() {
        MeetingType t = type(5, 10);
        hostRow(OWNER, MeetingTypeHost.CREATOR, null);
        // 1 creator + 9 cohosts = 10
        when(hosts.countForType(TYPE_ID)).thenReturn((long) MeetingHosts.MAX_HOSTS);
        AppUser extra = user(20L, "overflow");
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> meetingHosts.addCohost(t, extra));
    }

    /**
     * ADR-0002: the max is over the overrides actually SET; an unset one is not a 10-minute floor.
     */
    @Test
    void neitherSetFallsBackToTheTypeBuffer() {
        MeetingType t = bufferSeed(null, null);
        assertThat(meetingHosts.effectiveBufferBefore(t, OWNER, 120)).isEqualTo(10);
        assertThat(meetingHosts.effectiveBufferAfter(t, OWNER, 120)).isEqualTo(10);
    }

    @Test
    void aHostOverrideBelowTheTypeDefaultIsNotRaised() {
        MeetingType t = bufferSeed(5, null);
        assertThat(meetingHosts.effectiveBufferBefore(t, OWNER, 120)).isEqualTo(5);
    }

    @Test
    void aDurationOverrideAppliesWhenTheHostHasNone() {
        MeetingType t = bufferSeed(null, 45);
        assertThat(meetingHosts.effectiveBufferBefore(t, OWNER, 120)).isEqualTo(45);
    }

    @Test
    void theLargerOfTwoSetOverridesWins() {
        MeetingType t = bufferSeed(5, 45);
        assertThat(meetingHosts.effectiveBufferBefore(t, OWNER, 120)).isEqualTo(45);

        MeetingType u = bufferSeed(90, 45);
        assertThat(meetingHosts.effectiveBufferBefore(u, OWNER, 120)).isEqualTo(90);
    }
}
