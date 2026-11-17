package site.asm0dey.calit.domain;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BookingFieldRepository implements PanacheRepositoryBase<BookingField, Long> {
    public List<BookingField> formFor(Long ownerId, Long meetingTypeId) {
        return BookingField.formFor(ownerId, meetingTypeId);
    }
}
