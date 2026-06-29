package site.asm0dey.calit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
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
}
