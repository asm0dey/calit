package site.asm0dey.calit.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static site.asm0dey.calit.calendar.CalendarProvider.CALDAV;
import static site.asm0dey.calit.calendar.CalendarProvider.GOOGLE;
import module java.base;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.calendar.CalendarProvider;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.MailPlan.Recipient;

class MailPlanTest {
    private static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("he"));
    private static final UUID G = UUID.randomUUID();
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static OwnerSettings host(long id, String locale, boolean notify) {
        var s = new OwnerSettings();
        s.ownerId = id;
        s.ownerName = "Host" + id;
        s.ownerEmail = "host" + id + "@example.com";
        s.timezone = "Asia/Jerusalem";
        s.locale = locale;
        s.timeFormat = "h23";
        s.ownerNotificationsEnabled = notify;
        return s;
    }

    private static Booking row(long id, long owner, UUID group) {
        var b = new Booking();
        b.id = id;
        b.ownerId = owner;
        b.groupId = group;
        b.inviteeName = "Ivy";
        b.inviteeEmail = "ivy@example.com";
        b.locale = "en";
        return b;
    }

    private static BookingSnapshot single(boolean ownerNotify) {
        var mt = new MeetingType();
        mt.ownerId = 1L;
        return new BookingSnapshot(
                row(10, 1, null),
                mt,
                host(1, "de", ownerNotify),
                ZONE,
                List.of(),
                List.of(),
                null,
                List.of()
        );
    }

    /**
     * Group booking created by host 1, co-hosted by host 2 and host 3 (3 opted out).
     */
    private static BookingSnapshot group() {
        var mt = new MeetingType();
        mt.ownerId = 1L;
        var lead = row(10, 1, G);
        var deliveries = List.of(
                new HostDelivery(host(1, "de", true), lead),
                new HostDelivery(host(2, "he", true), row(11, 2, G)),
                new HostDelivery(host(3, "en", false), row(12, 3, G))
        );
        return new BookingSnapshot(lead, mt, host(1, "de", true), ZONE, List.of(), deliveries, null, List.of());
    }

    private static List<Recipient> plan(BookingSnapshot l, CalendarProvider mirror, boolean withIcs, boolean actionable) {
        return MailPlan.plan(l, mirror, withIcs, actionable, SUPPORTED);
    }

    @Test
    void singleHostSendsInviteeThenOwner() {
        var l = single(true);

        assertThat(plan(l, null, true, false))
            .extracting(
                    Recipient::role,
                    Recipient::email,
                    Recipient::locale,
                    Recipient::zone,
                    Recipient::greetingName,
                    Recipient::linkBooking,
                    Recipient::hourCycle,
                    Recipient::attachIcs,
                    Recipient::tag
            )
            .containsExactly(
                    tuple(
                            EmailService.INVITEE_ROLE,
                            "ivy@example.com",
                            Locale.ENGLISH,
                            ZONE,
                            "Ivy",
                            l.booking(),
                            "auto",
                            true,
                            MailTag.forBooking(10L, 1L)
                    ),
                    tuple(
                            EmailService.OWNER_ROLE,
                            "host1@example.com",
                            Locale.GERMAN,
                            ZONE,
                            "Host1",
                            l.booking(),
                            "h23",
                            true,
                            MailTag.forBooking(10L, 1L)
                    )
            );
    }

    @Test
    void inviteeLocaleComesFromTheBooking() {
        var l = single(true);
        l.booking().locale = "he";

        assertThat(plan(l, null, true, false).getFirst().locale()).isEqualTo(Locale.forLanguageTag("he"));
    }

    @Test
    void singleHostOptedOutOwnerGetsNothingEvenWhenActionable() {
        assertThat(plan(single(false), null, true, true))
            .extracting(Recipient::role)
            .containsExactly(EmailService.INVITEE_ROLE);
        // ported from EmailServiceTest.ownerOptedOutGetsNothingInviteeStillFallbackWhenDisconnected (confirmed mail)
        assertThat(plan(single(false), null, true, false))
            .extracting(Recipient::email, Recipient::attachIcs)
            .containsExactly(tuple("ivy@example.com", true));
    }

    @Test
    void groupOptedOutHostIsSkippedUnlessActionable() {
        var l = group();

        assertThat(plan(l, null, true, false))
            .extracting(Recipient::email)
            .containsExactly("ivy@example.com", "host1@example.com", "host2@example.com");
        assertThat(plan(l, null, true, true))
            .extracting(Recipient::email)
            .containsExactly("ivy@example.com", "host1@example.com", "host2@example.com", "host3@example.com");
    }

    @Test
    void groupHostCopyIsPersonalisedToThatHost() {
        var l = group();
        var cohost = plan(l, null, true, false).get(2);

        assertThat(cohost)
            .extracting(
                    Recipient::role,
                    Recipient::locale,
                    Recipient::zone,
                    Recipient::greetingName,
                    Recipient::linkBooking,
                    Recipient::hourCycle,
                    Recipient::tag
            )
            .containsExactly(
                    EmailService.OWNER_ROLE,
                    Locale.forLanguageTag("he"),
                    ZoneId.of("Asia/Jerusalem"),
                    "Host2",
                    l.hostDeliveries().get(1).booking(),
                    "h23",
                    MailTag.forBooking(11L, 2L)
            );
    }

    @Test
    void invalidHostTimezoneDoesNotStopTheInviteeCopy() {
        var l = group();
        l.hostDeliveries().get(1).settings().timezone = "Not/AZone";

        var recipients = plan(l, null, true, false);

        assertThat(recipients.getFirst())
            .extracting(Recipient::email, Recipient::zone)
            .containsExactly("ivy@example.com", ZONE);
        // the throw comes only when that host's copy is delivered, after the invitee's
        assertThatThrownBy(() -> recipients.get(2).zone()).isInstanceOf(DateTimeException.class);
    }

    @Test
    void googleMirrorAttachesNoIcsToAnyone() {
        assertThat(plan(group(), GOOGLE, true, true)).extracting(Recipient::attachIcs).containsOnly(false);
        assertThat(plan(single(true), GOOGLE, true, false)).extracting(Recipient::attachIcs).containsOnly(false);
    }

    @Test
    void caldavGroupCreatorGetsNoIcsCoHostsDo() {
        assertThat(plan(group(), CALDAV, true, true))
            .extracting(Recipient::email, Recipient::attachIcs)
            .containsExactly(
                    tuple("ivy@example.com", true),
                    tuple("host1@example.com", false),
                    tuple("host2@example.com", true),
                    tuple("host3@example.com", true)
            );
    }

    @Test
    void caldavSingleHostOwnerGetsNoIcsInviteeDoes() {
        assertThat(plan(single(true), CALDAV, true, false)).extracting(Recipient::attachIcs).containsExactly(
                true,
                false
        );
    }

    @Test
    void noIcsMethodMeansNoAttachment() {
        assertThat(plan(group(), null, false, true)).extracting(Recipient::attachIcs).containsOnly(false);
        assertThat(plan(single(true), null, false, false)).extracting(Recipient::attachIcs).containsOnly(false);
    }

    @Test
    void awaitingFirstInviteOnlyForConnectedNeverInvitedRequest() {
        assertThat(MailPlan.awaitingFirstInvite(null, 0, true)).isTrue();
        assertThat(MailPlan.awaitingFirstInvite(GOOGLE, 0, true)).isFalse();
        assertThat(MailPlan.awaitingFirstInvite(null, 1, true)).isFalse();
        assertThat(MailPlan.awaitingFirstInvite(null, 0, false)).isFalse();
    }
}
