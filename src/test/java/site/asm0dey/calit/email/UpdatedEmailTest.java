package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

@QuarkusTest
class UpdatedEmailTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    @InjectMock
    MailSender mailSender;

    @Transactional
    Booking seed(String slug) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.ownerNotificationsEnabled = true;
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Type Name";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1";
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService
                .availableSlots(t, LocalDate.now(), LocalDate.now().plusDays(14))
                .getFirst();
        return bookingService.book(
                1L, slug, slot.start().toInstant(), "Pat", "pat@example.com", Map.of(), "", "", "en", List.of());
    }

    @Test
    void detailsChangeEmailsBothPartiesWithNewNameAndDescription() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = seed("upd-mail");
        clearInvocations(mailSender);

        bookingService.updateDetails(b.manageToken, "Roadmap sync", "Q3 planning agenda", List.of(), true);

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender, atLeast(2)).send(any(), to.capture(), subject.capture(), body.capture(), any());
        assertTrue(to.getAllValues().contains("pat@example.com"), "invitee notified");
        assertTrue(to.getAllValues().contains("owner@example.com"), "owner notified");
        assertTrue(subject.getAllValues().stream().anyMatch(su -> su.contains("Roadmap sync")), "subject has new name");
        assertTrue(
                body.getAllValues().stream().anyMatch(bo -> bo.contains("Q3 planning agenda")), "body has description");
    }
}
