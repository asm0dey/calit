package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.BookingField.FieldType;
import site.asm0dey.calit.domain.MeetingType.LocationType;

class DisplayExtensionsTest {
    @Test
    void humanizesUpperSnakeEnums() {
        assertThat(DisplayExtensions.display(LocationType.GOOGLE_MEET)).isEqualTo("Google Meet");
        assertThat(DisplayExtensions.display(LocationType.IN_PERSON)).isEqualTo("In Person");
        assertThat(DisplayExtensions.display(LocationType.PHONE)).isEqualTo("Phone");
        assertThat(DisplayExtensions.display(FieldType.LONG_TEXT)).isEqualTo("Long Text");
        assertThat(DisplayExtensions.display(FieldType.SHORT_TEXT)).isEqualTo("Short Text");
        assertThat(DisplayExtensions.display(DayOfWeek.MONDAY)).isEqualTo("Monday");
    }

    @Test
    void nullRendersAsEmptyString() {
        assertThat(DisplayExtensions.display(null)).isEmpty();
    }

    @Test
    void whenFormatsInstantInGivenZoneWithZoneNameSuffix() {
        var i = Instant.parse("2026-08-20T13:00:00Z");
        // Asia/Tokyo has no DST, so this is stable regardless of when the test runs.
        String out = DisplayExtensions.when(i, "Asia/Tokyo");
        assertThat(out).isEqualTo("Thursday, 20 August 2026 at 22:00 (JST)");
    }

    @Test
    void whenNullInstantRendersAsEmptyString() {
        assertThat(DisplayExtensions.when(null, "UTC")).isEmpty();
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
        // DateTimeException for blank, NullPointerException for null. assertSoftly reports every
        // failing input in one run rather than stopping at the first.
        assertSoftly(s -> {
            s.assertThat(DisplayExtensions.when(i, "Not/AZone")).as("unknown zone id must fall back to UTC").contains(
                    "(UTC)"
            );
            s.assertThat(DisplayExtensions.when(i, "")).as("blank zone id must fall back to UTC").contains("(UTC)");
            s.assertThat(DisplayExtensions.when(i, null)).as("null zone id must fall back to UTC").contains("(UTC)");
        });
    }
}
