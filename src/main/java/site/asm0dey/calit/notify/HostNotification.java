package site.asm0dey.calit.notify;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.i18n.AppLocales;

/**
 * One host-facing event, ready to render onto a channel. The interface guarantees only
 * {@link #recipient()} and {@link #kind()}, NOT a booking: {@link ConsentRequested} invites someone
 * to <em>become</em> a co-host and has no booking, so a common {@code booking()} accessor would
 * force a null or a fake. The exhaustive switch in {@link ChannelMessageRenderer} handles it.
 *
 * <p>{@link #kind()} does double duty: exhaustive-switch discriminator and the {@code status}
 * string on the wire, so a {@code webhook://} consumer sees {@code "BOOKING_REQUESTED"}, not prose.
 */
public sealed interface HostNotification {

    Host recipient();

    String kind();

    /** The recipient, flattened off the (detached) OwnerSettings row the sync side already read. */
    record Host(Long ownerId, Locale locale, ZoneId zone, String hourCycle) {
        public static Host of(OwnerSettings settings) {
            return new Host(
                    settings.ownerId,
                    AppLocales.pick(settings.locale),
                    ZoneId.of(OwnerSettings.coerceZone(settings.timezone)),
                    settings.timeFormat);
        }
    }

    record Requested(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_REQUESTED";
        }
    }

    record Confirmed(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_CONFIRMED";
        }
    }

    record Approved(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_APPROVED";
        }
    }

    record Declined(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_DECLINED";
        }
    }

    record Cancelled(BookingSnapshot b, Host recipient, boolean byOwner) implements HostNotification {
        public String kind() {
            return "BOOKING_CANCELLED";
        }
    }

    record Rescheduled(BookingSnapshot b, Host recipient, Instant oldStartUtc, boolean byOwner)
            implements HostNotification {
        public String kind() {
            return "BOOKING_RESCHEDULED";
        }
    }

    record DetailsChanged(BookingSnapshot b, Host recipient, boolean byOwner) implements HostNotification {
        public String kind() {
            return "BOOKING_UPDATED";
        }
    }

    record GuestDeclined(BookingSnapshot b, Host recipient, BookingGuest guest) implements HostNotification {
        public String kind() {
            return "GUEST_DECLINED";
        }
    }

    record GuestRemoved(BookingSnapshot b, Host recipient, BookingGuest guest) implements HostNotification {
        public String kind() {
            return "GUEST_REMOVED";
        }
    }

    record ReminderDue(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_REMINDER";
        }
    }

    /**
     * The one event with no booking: a pending co-host row was created and this host must accept.
     * They are notified on their inherited channel set — they cannot have overridden a type they do
     * not host yet, so the routing rule covers it with no special case.
     */
    record ConsentRequested(MeetingType meetingType, Host recipient, String consentToken) implements HostNotification {
        public String kind() {
            return "HOST_CONSENT_REQUESTED";
        }
    }
}
