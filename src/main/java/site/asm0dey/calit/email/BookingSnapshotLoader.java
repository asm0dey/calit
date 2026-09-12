package site.asm0dey.calit.email;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.MeetingHosts;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * Loads the {@link BookingSnapshot} every booking-triggered side effect needs. Extracted from
 * {@code EmailService} so the outbound-notification path reads the same rows through the same
 * code -- group bookings, per-host rows and answer rendering are subtle enough that two copies
 * would drift.
 */
@ApplicationScoped
public class BookingSnapshotLoader {

    final MeetingHosts meetingHosts;

    @Inject
    public BookingSnapshotLoader(MeetingHosts meetingHosts) {
        this.meetingHosts = meetingHosts;
    }

    /**
     * Loads the booking + meeting type + owner settings + answers in the CALLER's active
     * transaction. Use from an already-transactional caller (the scheduler claim tx). Returns null
     * if gone. For a group booking also eagerly resolves every host's own {@link OwnerSettings} +
     * own booking row, because the per-host fan-out runs OUTSIDE this transaction.
     */
    public BookingSnapshot read(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            return null;
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
        if (owner == null) {
            // No settings row means no address to send the owner copy to, so there is nothing to
            // build. Returning null gives every caller the same "nothing to send" path the
            // missing-booking case already takes, instead of an NPE on owner.timezone (calit-sv6a).
            Log.warnf("no owner_settings for owner %d -- skipping mail for booking %d", type.ownerId, booking.id);
            return null;
        }
        // coerceZone, not a bare ZoneId.of: a row written before the save-time guard existed can
        // still hold an unparseable zone, and a DateTimeException here would take out every mail
        // for that owner, not just this one (calit-4whp).
        ZoneId zone = ZoneId.of(OwnerSettings.coerceZone(owner.timezone));
        List<EmailService.AnswerLine> answers = buildAnswerLines(booking, type);
        List<HostDelivery> hostDeliveries =
                booking.groupId == null ? List.of() : loadHostDeliveries(booking.groupId, type);
        return new BookingSnapshot(booking, type, owner, zone, answers, hostDeliveries);
    }

    /** As {@link #read} but opens its own transaction -- for AFTER_SUCCESS observers (no active tx). */
    public BookingSnapshot load(Long bookingId) {
        return QuarkusTransaction.requiringNew().call(() -> read(bookingId));
    }

    /** Every accepted host's own {@code OwnerSettings} paired with their own row of this group. */
    private List<HostDelivery> loadHostDeliveries(UUID groupId, MeetingType type) {
        List<Booking> rows = Booking.group(groupId);
        List<HostDelivery> deliveries = new ArrayList<>();
        for (Long hostId : meetingHosts.hostOwnerIds(type)) {
            OwnerSettings settings = OwnerSettings.forOwner(hostId);
            if (settings == null) continue;
            Booking row = rows.stream()
                    .filter(r -> hostId.equals(r.ownerId))
                    .findFirst()
                    .orElse(null);
            if (row == null) continue;
            deliveries.add(new HostDelivery(settings, row));
        }
        return deliveries;
    }

    /**
     * Joins {@code BookingField.formFor(meetingTypeId)} (ordered by {@code position}) to
     * {@code booking.answers} by {@code fieldKey}, skipping blank/absent values. Must run inside a
     * transaction -- the {@code requiringNew()} one opened by {@link #load} or the caller's own.
     */
    private static List<EmailService.AnswerLine> buildAnswerLines(Booking booking, MeetingType type) {
        List<EmailService.AnswerLine> lines = new ArrayList<>();
        Map<String, String> answers = booking.answers;
        if (answers == null || answers.isEmpty()) {
            return lines;
        }
        for (BookingField field : BookingField.formFor(type.ownerId, booking.meetingTypeId)) {
            String value = answers.get(field.fieldKey);
            if (value != null && !value.isBlank()) {
                lines.add(new EmailService.AnswerLine(field.label, value));
            }
        }
        return lines;
    }
}
