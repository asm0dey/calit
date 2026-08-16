package site.asm0dey.calit.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.BookingField.FieldType;
import site.asm0dey.calit.domain.MeetingType.LocationType;

class DisplayExtensionsTest {

    @Test
    void humanizesUpperSnakeEnums() {
        assertEquals("Google Meet", DisplayExtensions.display(LocationType.GOOGLE_MEET));
        assertEquals("In Person", DisplayExtensions.display(LocationType.IN_PERSON));
        assertEquals("Phone", DisplayExtensions.display(LocationType.PHONE));
        assertEquals("Long Text", DisplayExtensions.display(FieldType.LONG_TEXT));
        assertEquals("Short Text", DisplayExtensions.display(FieldType.SHORT_TEXT));
        assertEquals("Monday", DisplayExtensions.display(DayOfWeek.MONDAY));
    }

    @Test
    void nullRendersAsEmptyString() {
        assertEquals("", DisplayExtensions.display(null));
    }

    @Test
    void whenFormatsInstantInGivenZoneWithZoneNameSuffix() {
        var i = Instant.parse("2026-08-20T13:00:00Z");
        // Asia/Tokyo has no DST, so this is stable regardless of when the test runs.
        String out = DisplayExtensions.when(i, "Asia/Tokyo");
        assertEquals("Thursday, 20 August 2026 at 22:00 (JST)", out);
    }

    @Test
    void whenNullInstantRendersAsEmptyString() {
        assertEquals("", DisplayExtensions.when(null, "UTC"));
    }

    /**
     * OwnerSettings.timezone is stored straight from the form with no validation, so a garbage
     * zone id must fall back to UTC instead of throwing DateTimeException and 500-ing the whole
     * dashboard.
     */
    @Test
    void whenUnusableZoneIdFallsBackToUtcInsteadOfThrowing() {
        var i = Instant.parse("2026-08-20T13:00:00Z");
        // ZoneId.of throws a different exception per input: ZoneRulesException for an unknown id,
        // DateTimeException for blank, NullPointerException for null. assertAll reports every
        // failing input in one run rather than stopping at the first.
        assertAll(
                () -> assertTrue(
                        DisplayExtensions.when(i, "Not/AZone").contains("(UTC)"),
                        "unknown zone id must fall back to UTC; got: " + DisplayExtensions.when(i, "Not/AZone")),
                () -> assertTrue(
                        DisplayExtensions.when(i, "").contains("(UTC)"),
                        "blank zone id must fall back to UTC; got: " + DisplayExtensions.when(i, "")),
                () -> assertTrue(
                        DisplayExtensions.when(i, null).contains("(UTC)"),
                        "null zone id must fall back to UTC; got: " + DisplayExtensions.when(i, null)));
    }
}
