package site.asm0dey.calit.scheduler;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(PendingExpiryGraceWindowTest.Grace120Profile.class)
class PendingExpiryGraceWindowTest {

    public static class Grace120Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.scheduler.grace-seconds", "120");
        }
    }

    @Inject
    PendingExpiryScheduler scheduler;

    @BeforeEach
    void clearBookings() {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking.deleteAll();
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
        });
    }

    @Test
    void expiresPendingWhoseExpiryIsWithinGraceWindow() {
        Long meetingTypeId = seedMeetingType();

        // Created now, starts in 60s -> expiry = LEAST(createdAt+24h, startUtc) = +60s. Grace 120s -> expire.
        Long withinGrace = seedBooking(meetingTypeId, Instant.now(),
                Instant.now().plus(60, ChronoUnit.SECONDS), BookingStatus.PENDING);
        // Created now, starts in 1h -> expiry = +1h, beyond grace -> keep.
        Long beyondGrace = seedBooking(meetingTypeId, Instant.now(),
                Instant.now().plus(1, ChronoUnit.HOURS), BookingStatus.PENDING);

        scheduler.expirePendingBookings();

        assertEquals(BookingStatus.DECLINED, reloadStatus(withinGrace), "expiry within grace must be declined");
        assertEquals(BookingStatus.PENDING, reloadStatus(beyondGrace),  "expiry beyond grace must stay pending");
    }

    private Long seedMeetingType() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "expgrace-" + System.nanoTime();
            t.slug = "expgrace-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            return t.id;
        });
    }

    private Long seedBooking(Long meetingTypeId, Instant createdAt, Instant startUtc, BookingStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = meetingTypeId;
            b.inviteeName = "Sam";
            b.inviteeEmail = "sam@example.com";
            b.startUtc = startUtc;
            b.endUtc = startUtc.plusSeconds(1800);
            b.status = status;
            b.createdAt = createdAt;
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    private BookingStatus reloadStatus(Long id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> ((Booking) Booking.findById(id)).status);
    }
}
