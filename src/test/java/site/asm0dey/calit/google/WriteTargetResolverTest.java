package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarRef;
import site.asm0dey.calit.calendar.SelectedCalendar;
import site.asm0dey.calit.calendar.WriteTargetResolver;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.user.AppUser;

/**
 * Resolution order for a write: the (type, host) override when it still names one of that owner's
 * selected calendars, else that owner's write target. A dangling override degrades to the write
 * target instead of failing the booking.
 */
@QuarkusTest
class WriteTargetResolverTest {
    @Inject
    WriteTargetResolver resolver;

    @Test
    @TestTransaction
    void aNullOwnerHasNoOverrideRatherThanThrowing() {
        MeetingType t = seedType(1L);
        t.overrideAccountId = 1L;
        t.overrideRemoteCalendarId = "work@example.com";
        // No owner means no override to read — never a NullPointerException.
        assertThat(resolver.writeOverride(null, t)).isNull();
    }

    @Test
    @TestTransaction
    void noOverrideResolvesToTheWriteTarget() {
        var credId = seedCredential("sub-res-none");
        seedCalendar(1L, credId, "default@example.com", true, true);
        MeetingType t = seedType(1L);

        assertThat(resolver.writeOverride(1L, t)).isNull();
        assertThat(resolver.resolve(1L, t)).isEqualTo(new CalendarRef(credId, "default@example.com"));
    }

    @Test
    @TestTransaction
    void creatorOverrideWins() {
        var credId = seedCredential("sub-res-creator");
        seedCalendar(1L, credId, "default@example.com", true, true);
        seedCalendar(1L, credId, "work@example.com", false, true);
        MeetingType t = seedType(1L);
        t.overrideAccountId = credId;
        t.overrideRemoteCalendarId = "work@example.com";
        t.persistAndFlush();

        assertThat(resolver.resolve(1L, t)).isEqualTo(new CalendarRef(credId, "work@example.com"));
    }

    @Test
    @TestTransaction
    void aCohostHasNoOverrideAndWritesOnTheirOwnDefault() {
        var creatorCred = seedCredential("sub-res-creator-2");
        seedCalendar(1L, creatorCred, "creator@example.com", true, true);
        AppUser cohost = AppUser.create("cohost-resolver", "x", false);
        cohost.persist();
        Long cohostCred = seedCredential(cohost.id, "sub-res-cohost");
        seedCalendar(cohost.id, cohostCred, "cohost-default@example.com", true, true);
        seedCalendar(cohost.id, cohostCred, "cohost-work@example.com", false, true);

        MeetingType t = seedType(1L);
        t.persistAndFlush();
        MeetingTypeHost h = MeetingTypeHost.of(t.id, cohost.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED);
        h.persistAndFlush();
        // The creator writes on their own default; a co-host has no override (ADR 0007).
        assertThat(resolver.writeOverride(cohost.id, t)).isNull();
        assertThat(resolver.resolve(1L, t)).isEqualTo(new CalendarRef(creatorCred, "creator@example.com"));
        assertThat(resolver.resolve(cohost.id, t)).isEqualTo(new CalendarRef(cohostCred, "cohost-default@example.com"));
    }

    @Test
    @TestTransaction
    void danglingOverrideFallsBackToTheWriteTarget() {
        var credId = seedCredential("sub-res-dangling");
        seedCalendar(1L, credId, "default@example.com", true, true);
        MeetingType t = seedType(1L);
        t.overrideAccountId = credId;
        // no GoogleCalendar row: unticked since
        t.overrideRemoteCalendarId = "unticked@example.com";
        t.persistAndFlush();

        assertThat(resolver.resolve(1L, t)).isEqualTo(new CalendarRef(credId, "default@example.com"));
        assertThat(resolver.owns(1L, resolver.writeOverride(1L, t))).isFalse();
    }

    @Test
    @TestTransaction
    void aDisconnectedAccountLeavesADanglingOverrideNotAnUnsetOne() {
        // Disconnecting nulls meeting_type.google_credential_id via the FK and leaves the calendar
        // id behind. That half-row must still read as an override so the Host is told about it.
        var credId = seedCredential("sub-res-disconnected");
        seedCalendar(1L, credId, "default@example.com", true, true);
        MeetingType t = seedType(1L);
        t.overrideAccountId = null;
        t.overrideRemoteCalendarId = "was-on-a-disconnected-account@example.com";
        t.persistAndFlush();

        CalendarRef override = resolver.writeOverride(1L, t);
        assertThat(override.remoteCalendarId()).isEqualTo("was-on-a-disconnected-account@example.com");
        assertThat(override.accountId()).isNull();
        assertThat(resolver.owns(1L, override)).isFalse();
        assertThat(resolver.resolve(1L, t)).isEqualTo(new CalendarRef(credId, "default@example.com"));
    }

    @Test
    @TestTransaction
    void noCalendarAtAllResolvesToNull() {
        MeetingType t = seedType(1L);

        assertThat(resolver.resolve(1L, t)).isNull();
    }

    @Test
    @TestTransaction
    void meetGateFollowsTheResolvedCalendar() {
        var credId = seedCredential("sub-res-meet");
        // default cannot Meet
        seedCalendar(1L, credId, "default@example.com", true, false);
        // override can
        seedCalendar(1L, credId, "meet@example.com", false, true);
        MeetingType t = seedType(1L);
        t.overrideAccountId = credId;
        t.overrideRemoteCalendarId = "meet@example.com";
        t.persistAndFlush();

        assertThat(resolver.blocksMeet(1L, t)).isFalse();
        // a type with no override sees the default
        assertThat(resolver.blocksMeet(1L, seedType(1L))).isTrue();
    }

    @Test
    @TestTransaction
    void meetAllowedOnCaldavButBlockedOnMeetlessGoogle() {
        var googleCred = seedCredential("sub-res-meetless");
        seedCalendar(1L, googleCred, "meetless@example.com", false, false);
        var dav = new CaldavAccount();
        dav.ownerId = 1L;
        dav.serverUrl = "https://dav.example.com/";
        dav.username = "alice";
        dav.password = "secret";
        dav.principalUrl = "https://dav.example.com/p/alice/";
        dav.homeUrl = "https://dav.example.com/cal/";
        dav.persist();
        // a CalDAV calendar never mints Meet links; supportsMeet keeps its default
        seedCalendar(1L, dav.id, "/cal/work/", true, true);
        MeetingType onCaldav = seedType(1L);
        onCaldav.locationType = MeetingType.LocationType.GOOGLE_MEET;
        MeetingType onGoogle = seedType(1L);
        onGoogle.locationType = MeetingType.LocationType.GOOGLE_MEET;
        onGoogle.overrideAccountId = googleCred;
        onGoogle.overrideRemoteCalendarId = "meetless@example.com";
        onGoogle.persistAndFlush();

        assertThat(resolver.blocksMeet(1L, onCaldav)).isFalse();
        assertThat(resolver.meetUnavailable(1L, onCaldav)).isTrue();
        assertThat(resolver.blocksMeet(1L, onGoogle)).isTrue();
        assertThat(resolver.meetUnavailable(1L, onGoogle)).isFalse();
    }

    @Test
    void parsesTheFormValue() {
        assertThat(WriteTargetResolver.parseRef("7:a@example.com")).isEqualTo(new CalendarRef(7L, "a@example.com"));
        assertThat(WriteTargetResolver.parseRef("")).isNull();
        assertThat(WriteTargetResolver.parseRef(null)).isNull();
        assertThat(WriteTargetResolver.parseRef("nonsense")).isNull();
        assertThat(WriteTargetResolver.parseRef("x:a@example.com")).isNull();
    }

    private static Long seedCredential(String sub) {
        return seedCredential(1L, sub);
    }

    private static Long seedCredential(Long ownerId, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = ownerId;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        return c.id;
    }

    private static void seedCalendar(Long ownerId, Long credId, String calId, boolean writeTarget, boolean meet) {
        SelectedCalendar c = new SelectedCalendar();
        c.ownerId = ownerId;
        c.accountId = credId;
        c.remoteId = calId;
        c.summary = calId;
        c.readForBusy = writeTarget;
        c.writeTarget = writeTarget;
        c.supportsMeet = meet;
        c.persist();
    }

    private static MeetingType seedType(Long ownerId) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "resolver-seed";
        t.slug = "resolver-seed-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.persist();
        return t;
    }
}
