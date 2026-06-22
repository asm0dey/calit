package site.asm0dey.calit.booking;

import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarUnavailableException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class AvailableSlotsUnavailableTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);

    @Test
    @TestTransaction
    void unreadableCalendarFailsClosedInsteadOfShowingAllSlots() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "x"; t.slug = "unavail-x"; t.durationMinutes = 60;
        t.bufferBeforeMinutes = 0; t.bufferAfterMinutes = 0;
        t.minNoticeMinutes = 0; t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET; t.requiresApproval = false;
        t.persist();

        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L; r.dayOfWeek = DAY.getDayOfWeek(); r.meetingTypeId = null;
        r.startTime = LocalTime.parse("09:00"); r.endTime = LocalTime.parse("11:00");
        r.persist();

        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any()))
                .thenThrow(new CalendarUnavailableException("down"));

        assertThrows(CalendarUnavailableException.class,
                () -> bookingService.availableSlots(t, DAY, DAY));
    }
}
