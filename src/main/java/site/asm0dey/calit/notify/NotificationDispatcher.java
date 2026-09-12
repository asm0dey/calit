package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.time.Instant;
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

    final ChannelStamp stamp;

    final DeliveryExecutor executor;

    @Inject
    public NotificationDispatcher(
            BookingSnapshotLoader snapshots,
            ChannelRouter router,
            ChannelMessageRenderer renderer,
            ChannelPolicy policy,
            Event<ChannelDelivery> deliveries,
            ChannelStamp stamp,
            DeliveryExecutor executor) {
        this.snapshots = snapshots;
        this.router = router;
        this.renderer = renderer;
        this.policy = policy;
        this.deliveries = deliveries;
        this.stamp = stamp;
        this.executor = executor;
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

    // --- explicit dispatch: the two paths that have no event to observe ---

    /**
     * Reminders carry no CDI event. {@code ReminderScheduler} claims the due row, stamps {@code
     * sent_at} and enqueues the email in ONE transaction -- the durable-outbox path that replaced
     * the old {@code ReminderDue} event -- so there is nothing left to observe. The scheduler calls
     * this once per claimed booking AFTER that transaction commits; {@link #dispatch} swallows every
     * RuntimeException, so a channel problem can neither roll back nor delay the claim, and can
     * never cost the reminder email.
     */
    public void notifyReminder(Long bookingId) {
        dispatch(bookingId, HostNotification.ReminderDue::new);
    }

    /**
     * Same shape for an auto-expired approval: {@code PendingExpiryScheduler} flips PENDING ->
     * DECLINED and enqueues the declined email inside its own claim transaction WITHOUT firing
     * {@code BookingDeclined}, so {@link #onDeclined} never sees it and an owner-initiated decline
     * would otherwise be the only one reaching a channel. Called per declined booking after that
     * transaction commits.
     */
    public void notifyDeclined(Long bookingId) {
        dispatch(bookingId, HostNotification.Declined::new);
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
                // Record the skip as a failure: nothing else writes a timestamp on this path, so
                // /me/settings would keep rendering the stale green "last delivery OK" badge for a
                // channel that silently stopped delivering when the allowlist was tightened.
                // requiringNew, not the bare @Transactional call: an AFTER_SUCCESS observer runs in
                // the committing transaction's afterCompletion, where a REQUIRED interceptor joins a
                // caller transaction that is already complete and the UPDATE then fails with
                // TransactionRequiredException -- the same reason the channel lookup above opens its
                // own. Guarded so one unstampable row cannot abort the remaining channels.
                try {
                    var at = Instant.now();
                    QuarkusTransaction.requiringNew().run(() -> stamp.stamp(c.id, false, at));
                } catch (RuntimeException e) {
                    Log.warnf(e, "could not stamp channel %d", c.id);
                }
                continue;
            }
            // Never the shared worker pool -- see DeliveryExecutor for why.
            deliveries.fireAsync(new ChannelDelivery(c.id, c.url, message), executor.options());
        }
    }
}
