package site.asm0dey.calit.booking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarRef;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/** A booking's Google event is created on the meeting type's write calendar, not blindly on the default. */
@QuarkusTest
class BookingWriteTargetOverrideTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY =
            Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();

    @Test
    @TestTransaction
    void usesTheTypesOverride() {
        var credId = seedCredential("sub-book-override");
        seedCalendar(credId, "default@example.com", true);
        seedCalendar(credId, "work@example.com", false);
        stubGoogle();

        book("book-override", credId, "work@example.com");

        verify(calendarPort)
                .createEvent(
                        eq(1L),
                        eq(new CalendarRef(credId, "work@example.com")),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any());
    }

    @Test
    @TestTransaction
    void withoutAnOverrideUsesTheWriteTarget() {
        var credId = seedCredential("sub-book-default");
        seedCalendar(credId, "default@example.com", true);
        stubGoogle();

        book("book-default", null, null);

        verify(calendarPort)
                .createEvent(
                        eq(1L),
                        eq(new CalendarRef(credId, "default@example.com")),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any());
    }

    @Test
    @TestTransaction
    void aDanglingOverrideStillBooksOnTheDefault() {
        var credId = seedCredential("sub-book-dangling");
        seedCalendar(credId, "default@example.com", true);
        stubGoogle();

        book("book-dangling", credId, "unticked@example.com");

        verify(calendarPort)
                .createEvent(
                        eq(1L),
                        eq(new CalendarRef(credId, "default@example.com")),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any());
    }

    @Test
    @TestTransaction
    void degradedModeCreatesNoEvent() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        book("book-degraded", null, null);

        verify(calendarPort, never())
                .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    /**
     * Group path, per-Host scoping: the organizer is a CO-HOST (not the creator), and the two
     * hosts carry DIFFERENT overrides. The event must land on the organizer's (cohost's) own
     * override, never the creator's -- this is the property that guards against writing one
     * user's meeting into another user's calendar.
     */
    @Test
    @TestTransaction
    void groupOrganizerUsesTheCohostsOwnOverrideNotTheCreators() {
        var creatorCredId = seedCredential(1L, "sub-group-creator");
        seedCalendar(1L, creatorCredId, "creator-default@example.com", true);
        seedCalendar(1L, creatorCredId, "creator-override@example.com", false);

        AppUser cohost = MultiHostFixtures.enabledUser("cohost-group-override");
        Long cohostId = cohost.id;
        var cohostCredId = seedCredential(cohostId, "sub-group-cohost");
        seedCalendar(cohostId, cohostCredId, "cohost-default@example.com", true);
        seedCalendar(cohostId, cohostCredId, "cohost-override@example.com", false);

        MultiHostFixtures.settings(1L, "owner");
        MultiHostFixtures.settings(cohostId, "cohost");
        MultiHostFixtures.rule(1L, DAY.getDayOfWeek(), 9, 11);
        MultiHostFixtures.rule(cohostId, DAY.getDayOfWeek(), 9, 11);

        MeetingType t = MultiHostFixtures.acceptedTwoHostType(1L, cohostId, "group-override", 60, false);
        t.locationType = MeetingType.LocationType.PHONE;
        // Creator's own override -- set directly on MeetingType (see book()'s comment below).
        t.googleCredentialId = creatorCredId;
        t.googleCalendarId = "creator-override@example.com";
        t.persist();
        // Cohost's own override -- lives on their MeetingTypeHost row, not on MeetingType.
        MeetingTypeHost cohostRow = MeetingTypeHost.find(t.id, cohostId);
        cohostRow.googleCredentialId = cohostCredId;
        cohostRow.googleCalendarId = "cohost-override@example.com";
        cohostRow.persist();

        // Only the cohost is Google-connected. MeetingHosts.chooseOrganizer picks the creator ONLY
        // when the creator is connected, else the lowest-id connected host -- with the creator
        // disconnected here, the cohost is the sole connected candidate, so it is unambiguously the
        // organizer. Not a coin flip between hosts.
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                        anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", null, null, null));

        bookingService.book(1L, t.slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());

        verify(calendarPort)
                .createEvent(
                        eq(cohostId),
                        eq(new CalendarRef(cohostCredId, "cohost-override@example.com")),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any());
    }

    private void stubGoogle() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                        anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", null, null, null));
    }

    /**
     * Seed owner settings + a 09:00-11:00 type (with the given override) on DAY, then book 09:00.
     * The override itself is just {@code MeetingType.googleCredentialId}/{@code googleCalendarId}
     * set directly on the type row -- there is no separate override table for the creator's own
     * override (a co-host's override lives on their {@code MeetingTypeHost} row instead, see
     * {@link #groupOrganizerUsesTheCohostsOwnOverrideNotTheCreators}).
     */
    private Booking book(String slug, Long credId, String calendarId) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug + "-" + UUID.randomUUID();
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = MeetingType.LocationType.PHONE;
        t.googleCredentialId = credId;
        t.googleCalendarId = calendarId;
        t.persist();

        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = DAY.getDayOfWeek();
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();

        return bookingService.book(1L, t.slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
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

    private static void seedCalendar(Long credId, String calId, boolean writeTarget) {
        seedCalendar(1L, credId, calId, writeTarget);
    }

    private static void seedCalendar(Long ownerId, Long credId, String calId, boolean writeTarget) {
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = ownerId;
        c.googleCredentialId = credId;
        c.googleCalendarId = calId;
        c.summary = calId;
        c.readForBusy = writeTarget;
        c.writeTarget = writeTarget;
        c.persist();
    }
}
