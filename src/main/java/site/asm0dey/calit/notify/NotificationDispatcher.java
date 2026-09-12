package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.BiFunction;
import org.alexmond.notify4j.Message;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.email.BookingSnapshotLoader;
import site.asm0dey.calit.email.HostDelivery;

/**
 * The synchronous half of the delivery path. Everything needing the DB or a locale happens here, on
 * the request thread, where a transaction and a request context already exist; the async side does
 * one POST and one timestamp write and never reads through Panache.
 *
 * <p>{@code @ObservesAsync} and {@code during = AFTER_SUCCESS} are mutually exclusive in CDI — an
 * async observer cannot declare a transaction phase — which forces this two-step, and the two-step
 * is what we want anyway.
 */
@ApplicationScoped
// S6813: CDI field injection is the established pattern across this codebase's beans.
@SuppressWarnings("java:S6813")
public class NotificationDispatcher {

    final BookingSnapshotLoader snapshots;

    final ChannelRouter router;

    final ChannelMessageRenderer renderer;

    final ChannelPolicy policy;

    final Event<ChannelDelivery> deliveries;

    @Inject
    public NotificationDispatcher(
            BookingSnapshotLoader snapshots,
            ChannelRouter router,
            ChannelMessageRenderer renderer,
            ChannelPolicy policy,
            Event<ChannelDelivery> deliveries) {
        this.snapshots = snapshots;
        this.router = router;
        this.renderer = renderer;
        this.policy = policy;
        this.deliveries = deliveries;
    }

    // --- CDI observers: fire only after the booking transaction commits. ---

    void onRequested(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRequested e) {
        dispatch(e.bookingId(), HostNotification.Requested::new);
    }

    void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingConfirmed e) {
        dispatch(e.bookingId(), HostNotification.Confirmed::new);
    }

    void onApproved(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingApproved e) {
        dispatch(e.bookingId(), HostNotification.Approved::new);
    }

    void onDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDeclined e) {
        dispatch(e.bookingId(), HostNotification.Declined::new);
    }

    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingCancelled e) {
        dispatch(e.bookingId(), (s, h) -> new HostNotification.Cancelled(s, h, e.byOwner()));
    }

    void onRescheduled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRescheduled e) {
        dispatch(e.bookingId(), (s, h) -> new HostNotification.Rescheduled(s, h, e.oldStartUtc(), e.byOwner()));
    }

    void onDetailsChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDetailsChanged e) {
        dispatch(e.bookingId(), (s, h) -> new HostNotification.DetailsChanged(s, h, e.byOwner()));
    }

    void onGuestDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) GuestDeclined e) {
        BookingGuest g = guest(e.guestId());
        if (g == null) return;
        dispatch(e.bookingId(), (s, h) -> new HostNotification.GuestDeclined(s, h, g));
    }

    void onGuestRemoved(@Observes(during = TransactionPhase.AFTER_SUCCESS) GuestRemoved e) {
        BookingGuest g = guest(e.guestId());
        if (g == null) return;
        dispatch(e.bookingId(), (s, h) -> new HostNotification.GuestRemoved(s, h, g));
    }

    void onReminder(@Observes(during = TransactionPhase.AFTER_SUCCESS) ReminderDue e) {
        dispatch(e.bookingId(), HostNotification.ReminderDue::new);
    }

    /**
     * The candidate has not accepted this meeting type yet, so they are notified on their INHERITED
     * set — they cannot have overridden a type they do not host. That falls out of the routing rule
     * with no special case, because they own no link rows for it.
     */
    void onHostConsent(@Observes(during = TransactionPhase.AFTER_SUCCESS) HostConsentRequested e) {
        try {
            var loaded = QuarkusTransaction.requiringNew().call(() -> {
                MeetingType type = MeetingType.findById(e.meetingTypeId());
                OwnerSettings settings = OwnerSettings.forOwner(e.cohostOwnerId());
                return type == null || settings == null ? null : new Object[] {type, settings};
            });
            if (loaded == null) return;
            MeetingType type = (MeetingType) loaded[0];
            OwnerSettings settings = (OwnerSettings) loaded[1];
            fanOut(
                    settings,
                    type.id,
                    new HostNotification.ConsentRequested(type, HostNotification.Host.of(settings), e.consentToken()));
        } catch (RuntimeException ex) {
            Log.warn("channel notification failed for host consent", ex);
        }
    }

    // --- internals ---

    /**
     * Guarded like {@link #dispatch}: its own {@code requiringNew} transaction can fail (pool
     * exhaustion), and an escape here would land as an ERROR with a stack trace attributed to this
     * dispatcher instead of the single WARN the rest of the path emits.
     */
    private static BookingGuest guest(Long guestId) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> BookingGuest.findById(guestId));
        } catch (RuntimeException e) {
            Log.warnf(e, "channel notification failed loading guest %d", guestId);
            return null;
        }
    }

    /**
     * One notification per host: a single-host booking has no {@code hostDeliveries}, so the
     * snapshot's own owner is the only recipient; a group booking fans out to each accepted host's
     * own settings.
     */
    private void dispatch(Long bookingId, BiFunction<BookingSnapshot, HostNotification.Host, HostNotification> f) {
        try {
            BookingSnapshot s = snapshots.load(bookingId);
            if (s == null) return;
            List<OwnerSettings> recipients = s.hostDeliveries().isEmpty()
                    ? List.of(s.owner())
                    : s.hostDeliveries().stream().map(HostDelivery::settings).toList();
            for (OwnerSettings settings : recipients) {
                fanOut(settings, s.meetingType().id, f.apply(s, HostNotification.Host.of(settings)));
            }
        } catch (RuntimeException e) {
            // The booking is already committed and emailed: a channel problem must never surface
            // to the caller or abort the remaining observers.
            Log.warnf(e, "channel notification failed for booking %d", bookingId);
        }
    }

    private void fanOut(OwnerSettings settings, Long meetingTypeId, HostNotification n) {
        List<NotificationChannel> channels =
                QuarkusTransaction.requiringNew().call(() -> router.channelsFor(settings.ownerId, meetingTypeId));
        if (channels.isEmpty()) {
            return; // no channel URL means no channel notifications: email only, as today
        }
        Message message = renderer.render(n);
        for (NotificationChannel c : channels) {
            // url is already decrypted by the converter during the load above, so the async side
            // needs neither the entity nor a session.
            if (!policy.check(c.url).ok()) {
                Log.warnf("channel %d skipped: blocked by policy", c.id);
                continue;
            }
            deliveries.fireAsync(new ChannelDelivery(c.id, c.url, message));
        }
    }
}
