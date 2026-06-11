package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BookingFieldTest {

    @Test
    @TestTransaction
    void globalFormIncludesSeededDescription() {
        // No per-type fields for this id -> falls back to the global default form.
        List<BookingField> form = BookingField.formFor(1L, 999_999L);
        assertTrue(form.stream().anyMatch(f -> "description".equals(f.fieldKey)));
    }

    @Test
    @TestTransaction
    void perTypeFieldsOverrideGlobalAndKeepOrder() {
        // booking_field.meeting_type_id is a real FK, so persist a MeetingType first and use
        // its generated id (a literal id would violate the FK constraint).
        MeetingType type = new MeetingType();
        type.name = "BF Test";
        type.slug = "bookingfield-override-type";
        type.durationMinutes = 30;
        type.persist();

        BookingField company = field(type.id, "company", "Company",
                BookingField.FieldType.SHORT_TEXT, true, 1);
        company.persist();
        BookingField vat = field(type.id, "vat", "VAT ID",
                BookingField.FieldType.SHORT_TEXT, false, 0);
        vat.persist();

        List<BookingField> form = BookingField.formFor(1L, type.id);

        assertEquals(2, form.size());
        assertEquals("vat", form.get(0).fieldKey);     // position 0 first
        assertEquals("company", form.get(1).fieldKey); // global description NOT included
    }

    private BookingField field(Long typeId, String key, String label,
                               BookingField.FieldType type, boolean required, int position) {
        BookingField f = new BookingField();
        f.ownerId = 1L;
        f.meetingTypeId = typeId;
        f.fieldKey = key;
        f.label = label;
        f.type = type;
        f.required = required;
        f.position = position;
        return f;
    }
}
