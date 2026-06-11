package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.booking.events.BookingConfirmed;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@QuarkusTest
class EmailServiceEventWiringTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";

    @Inject
    Event<BookingConfirmed> confirmedEvent;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        // Clear prior HELD bookings so the seeded CONFIRMED row does not collide with the DB
        // exclusion constraint (booking_no_overlap_held) on overlapping HELD time ranges.
        QuarkusTransaction.requiringNew().run(() -> Booking.deleteAll());
    }

    @Test
    void firingConfirmedInCommittedTxTriggersObserverAndSendsTwoMails() {
        when(calendarPort.isConnected()).thenReturn(false); // disconnected -> invitee fallback fires

        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.name = "Wiring Call";
            t.slug = "wiring-" + System.nanoTime();
            t.durationMinutes = 45;
            t.locationType = LocationType.GOOGLE_MEET;
            t.persist();

            Booking b = new Booking();
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = Instant.parse("2026-06-08T09:00:00Z");
            b.endUtc = b.startUtc.plus(45, ChronoUnit.MINUTES);
            b.googleEventId = "evt-" + System.nanoTime();
            b.meetLink = "https://meet.google.com/wire-test";
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = "tok-" + System.nanoTime();
            b.createdAt = Instant.now();
            b.persist();

            confirmedEvent.fire(new BookingConfirmed(b.id));
        });

        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMailsSentTo(OWNER_EMAIL).size());
        assertEquals(2, mailbox.getTotalMessagesSent());
    }
}
