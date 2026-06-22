package site.asm0dey.calit.booking;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class BookingLocaleTest {

    @Inject
    BookingService bookingService;

    /** Seeds an owner with settings, a meeting type, and a wide weekly availability window. */
    private MeetingType seedOwnerAndType(String username) {
        AppUser u = new AppUser();
        u.username = username; u.passwordHash = "x"; u.roles = "user"; u.enabled = true; u.isAdmin = false;
        u.createdAt = java.time.Instant.now();
        u.persist();

        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id; s.ownerName = username; s.ownerEmail = username + "@x.com";
        s.timezone = "UTC";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = u.id; t.name = "Intro"; t.slug = "intro-" + username;
        t.durationMinutes = 30;
        t.persist();

        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = u.id; r.meetingTypeId = t.id; r.dayOfWeek = d;
            r.startTime = LocalTime.of(0, 0); r.endTime = LocalTime.of(23, 30);
            r.persist();
        }
        return t;
    }

    @Test
    @TestTransaction
    void bookStoresProvidedLocale() {
        MeetingType t = seedOwnerAndType("locale-de-user");
        ZonedDateTime slot = ZonedDateTime.now(ZoneId.of("UTC"))
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);

        Booking b = bookingService.book(t.ownerId, t.slug, slot.toInstant(),
                "Erika", "erika@example.de", Map.of(), null, null, "de");

        assertEquals("de", b.locale);
    }

    @Test
    @TestTransaction
    void bookFallsBackToEnForUnsupportedLocale() {
        MeetingType t = seedOwnerAndType("locale-xx-user");
        ZonedDateTime slot = ZonedDateTime.now(ZoneId.of("UTC"))
                .plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);

        Booking b = bookingService.book(t.ownerId, t.slug, slot.toInstant(),
                "Xavier", "xavier@example.com", Map.of(), null, null, "xx");

        assertEquals("en", b.locale);
    }

    @Test
    @TestTransaction
    void bookFallsBackToEnForNullLocale() {
        MeetingType t = seedOwnerAndType("locale-null-user");
        ZonedDateTime slot = ZonedDateTime.now(ZoneId.of("UTC"))
                .plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0);

        Booking b = bookingService.book(t.ownerId, t.slug, slot.toInstant(),
                "Nadia", "nadia@example.com", Map.of(), null, null, null);

        assertEquals("en", b.locale);
    }
}
