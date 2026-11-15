package site.asm0dey.calit.email;

import module java.base;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.calendar.CalendarProvider;
import site.asm0dey.calit.i18n.AppLocales;

/**
 * Pure recipient selection for one booking mail: who gets a copy, in which locale, zone and wording role, and
 * whether it carries calit's .ics. {@link EmailService} renders and delivers each {@link Recipient} in order.
 */
final class MailPlan {
    /**
     * One copy of a booking mail. {@code role} is {@link EmailService#INVITEE_ROLE} or
     * {@link EmailService#OWNER_ROLE}; {@code linkBooking} is the row whose tokens back the mail's links.
     */
    record Recipient(
            String email,
            String role,
            Locale locale,
            ZoneId zone,
            String greetingName,
            Booking linkBooking,
            String hourCycle,
            boolean attachIcs,
            MailTag tag
    ) {}

    private MailPlan() {
    }

    static List<Recipient> plan(BookingSnapshot l, CalendarProvider liveMirror, boolean withIcs, boolean actionable) {
        return plan(l, liveMirror, withIcs, actionable, AppLocales.supported());
    }

    /**
     * Invitee first, exactly once. A group booking adds each host in {@code hostDeliveries} order, skipping an
     * opted-out host unless the mail is {@code actionable} (an opted-out host must still approve/decline their row);
     * a single-host booking adds the owner iff {@code ownerNotificationsEnabled}. A Google Mirror event invites
     * everyone itself, so nobody gets calit's .ics; a CalDAV one already sits on the Creator's calendar, so only the
     * Creator goes without.
     */
    static List<Recipient> plan(
            BookingSnapshot l,
            CalendarProvider liveMirror,
            boolean withIcs,
            boolean actionable,
            List<Locale> supported
    ) {
        boolean ics = withIcs && liveMirror != CalendarProvider.GOOGLE;
        boolean creatorIcs = ics && liveMirror != CalendarProvider.CALDAV;
        var b = l.booking();
        var out = new ArrayList<Recipient>();
        out.add(
                new Recipient(
                        b.inviteeEmail,
                        EmailService.INVITEE_ROLE,
                        AppLocales.pick(b.locale, supported),
                        l.zone(),
                        b.inviteeName,
                        b,
                        "auto",
                        ics,
                        MailTag.forBooking(b.id, b.ownerId)
                )
        );
        if (b.groupId != null) {
            for (HostDelivery hd : l.hostDeliveries()) {
                var s = hd.settings();
                if (!actionable && !s.ownerNotificationsEnabled) {
                    continue;
                }
                out.add(
                        new Recipient(
                                s.ownerEmail,
                                EmailService.OWNER_ROLE,
                                AppLocales.pick(s.locale, supported),
                                ZoneId.of(s.timezone),
                                s.ownerName,
                                hd.booking(),
                                s.timeFormat,
                                s.ownerId.equals(l.meetingType().ownerId) ? creatorIcs : ics,
                                MailTag.forBooking(hd.booking().id, s.ownerId)
                        )
                );
            }
        } else if (l.owner().ownerNotificationsEnabled) {
            var o = l.owner();
            out.add(
                    new Recipient(
                            o.ownerEmail,
                            EmailService.OWNER_ROLE,
                            AppLocales.pick(o.locale, supported),
                            l.zone(),
                            o.ownerName,
                            b,
                            o.timeFormat,
                            creatorIcs,
                            MailTag.forBooking(b.id, o.ownerId)
                    )
            );
        }
        return out;
    }

    /**
     * A never-approved request of a connected owner: no Mirror event yet and no invite sent.
     */
    static boolean awaitingFirstInvite(CalendarProvider rawMirror, int icsSequence, boolean connected) {
        return rawMirror == null && icsSequence == 0 && connected;
    }
}
