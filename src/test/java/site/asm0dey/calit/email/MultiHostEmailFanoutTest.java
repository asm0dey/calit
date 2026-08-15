package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.booking.events.BookingRequested;
import site.asm0dey.calit.booking.events.HostConsentRequested;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * A group booking (booking.groupId != null) must fan out lifecycle mail: the invitee gets exactly
 * one copy (keyed on the lead row), and EACH host gets their own personalized owner-role copy
 * (their own locale/name via {@link OwnerSettings}, their own row's approve/manage tokens).
 * {@link HostConsentRequested} sends the co-host a one-click /consent/{token} link.
 */
@QuarkusTest
class MultiHostEmailFanoutTest {

    private static final String INVITEE_EMAIL = "invitee@example.com";
    private static final long CREATOR_ID = 1L; // DatabaseResetCallback baseline admin
    private static final long COHOST_ID = 2L;

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
    }

    private MeetingType twoHostType(boolean requiresApproval) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost");
            MultiHostFixtures.settings(CREATOR_ID, "Creator");
            MultiHostFixtures.settings(COHOST_ID, "Cohost");
            return MultiHostFixtures.acceptedTwoHostType(CREATOR_ID, COHOST_ID, "pair-up", 30, requiresApproval);
        });
    }

    /** One row per host, sharing a groupId; returns the lead (creator's) row id. */
    private long seedGroup(MeetingType type, BookingStatus status, boolean withApprovalTokens) {
        var groupId = UUID.randomUUID();
        var start = Instant.parse("2026-06-08T09:00:00Z");
        return QuarkusTransaction.requiringNew().call(() -> {
            long leadId = -1;
            for (long hostId : List.of(CREATOR_ID, COHOST_ID)) {
                Booking b = new Booking();
                b.ownerId = hostId;
                b.meetingTypeId = type.id;
                b.inviteeName = "Sam Invitee";
                b.inviteeEmail = INVITEE_EMAIL;
                b.startUtc = start;
                b.endUtc = start.plus(30, ChronoUnit.MINUTES);
                b.status = status;
                b.groupId = groupId;
                b.manageToken = "tok-" + hostId + "-" + System.nanoTime();
                if (withApprovalTokens) {
                    b.approvalToken = "appr-" + hostId + "-" + System.nanoTime();
                }
                b.createdAt = Instant.now();
                b.persist();
                if (hostId == CREATOR_ID) {
                    leadId = b.id;
                }
            }
            return leadId;
        });
    }

    @Test
    void autoConfirmedGroupBookingSendsOneInviteeMailAndOnePerHost() {
        MeetingType type = twoHostType(false);
        long leadId = seedGroup(type, BookingStatus.CONFIRMED, false);

        emailService.handleConfirmed(new BookingConfirmed(leadId));

        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size(), "invitee gets exactly one copy");
        assertEquals(1, mailbox.getMailsSentTo("Creator@x.com").size(), "creator host gets one copy");
        assertEquals(1, mailbox.getMailsSentTo("Cohost@x.com").size(), "co-host gets one copy");
        assertEquals(3, mailbox.getTotalMessagesSent(), "no duplicate invitee mail, no missed host");
    }

    @Test
    void approvalGroupBookingAlwaysEmailsEveryHostEvenWhenOptedOut() {
        MeetingType type = twoHostType(true);
        // Co-host opted out of notifications -- the approval-needed mail must still reach them,
        // otherwise nobody can ever approve their row and the group deadlocks.
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(COHOST_ID);
            s.ownerNotificationsEnabled = false;
            s.persist();
        });
        long leadId = seedGroup(type, BookingStatus.PENDING, true);

        emailService.handleRequested(new BookingRequested(leadId));

        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size(), "invitee gets exactly one copy");
        assertEquals(1, mailbox.getMailsSentTo("Creator@x.com").size(), "creator host gets the approval mail");
        assertEquals(
                1,
                mailbox.getMailsSentTo("Cohost@x.com").size(),
                "opted-out co-host still gets the approval-needed mail (actionable, not suppressible)");
        assertEquals(3, mailbox.getTotalMessagesSent());

        // Each host's mail must link to THEIR OWN group row (id + approvalToken), never the lead's --
        // a regression that hands every host the lead's token would still contain "/me/bookings/"
        // (weak substring check) but must fail this exact-link assertion.
        record RowTokens(long id, String approvalToken) {}
        RowTokens[] rows = QuarkusTransaction.requiringNew().call(() -> {
            List<Booking> group = Booking.group(Booking.<Booking>findById(leadId).groupId);
            Booking cohostRow = group.stream()
                    .filter(b -> b.ownerId.equals(COHOST_ID))
                    .findFirst()
                    .orElseThrow();
            Booking leadRow = group.stream()
                    .filter(b -> b.ownerId.equals(CREATOR_ID))
                    .findFirst()
                    .orElseThrow();
            return new RowTokens[] {
                new RowTokens(cohostRow.id, cohostRow.approvalToken), new RowTokens(leadRow.id, leadRow.approvalToken)
            };
        });
        var cohostRow = rows[0];
        var leadRow = rows[1];

        Mail cohostMail = mailbox.getMailsSentTo("Cohost@x.com").getFirst();
        var cohostExpectedLink = "/me/bookings/" + cohostRow.id() + "/approve?t=" + cohostRow.approvalToken();
        assertTrue(cohostMail.getHtml().contains(cohostExpectedLink), "carries this host's OWN approve link");
        assertFalse(
                cohostMail.getHtml().contains("/me/bookings/" + leadRow.id() + "/approve?t="),
                "must NOT carry the lead's approve link");

        Mail creatorMail = mailbox.getMailsSentTo("Creator@x.com").getFirst();
        var leadExpectedLink = "/me/bookings/" + leadRow.id() + "/approve?t=" + leadRow.approvalToken();
        assertTrue(creatorMail.getHtml().contains(leadExpectedLink), "lead host gets their own approve link too");
    }

    @Test
    void eachHostCopyUsesItsOwnHourCyclePreferenceNotTheLeadHosts() {
        MeetingType type = twoHostType(false);
        // Lead host stores h12, co-host stores auto -- each host's copy must reflect THEIR OWN
        // preference, never the lead's (the exact cross-host bleed the design forbids).
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings lead = OwnerSettings.forOwner(CREATOR_ID);
            lead.timezone = "UTC";
            lead.timeFormat = "h12";
            lead.persist();
            OwnerSettings cohost = OwnerSettings.forOwner(COHOST_ID);
            cohost.timezone = "UTC";
            cohost.timeFormat = "auto";
            cohost.persist();
        });
        long leadId = seedGroup(type, BookingStatus.CONFIRMED, false);

        emailService.handleConfirmed(new BookingConfirmed(leadId));

        // seedGroup starts the booking at 2026-06-08T09:00:00Z; with both hosts on UTC that's
        // 09:00 local -> 24h renders "09:00", 12h renders "9:00 AM".
        String leadHtml = mailbox.getMailsSentTo("Creator@x.com").getFirst().getHtml();
        assertTrue(leadHtml.contains("9:00 AM"), "lead host chose h12; got: " + leadHtml);

        String cohostHtml = mailbox.getMailsSentTo("Cohost@x.com").getFirst().getHtml();
        assertTrue(cohostHtml.contains("09:00"), "co-host chose auto (24h); got: " + cohostHtml);
        assertFalse(
                cohostHtml.contains("9:00 AM"),
                "lead host's h12 preference must not leak into the co-host's copy; got: " + cohostHtml);
    }

    @Test
    void hostConsentRequestedEmailsTheCohostWithAConsentLink() {
        MeetingType type = twoHostType(false);

        emailService.handleHostConsent(new HostConsentRequested(type.id, COHOST_ID, "tok-123"));

        List<Mail> toCohost = mailbox.getMailsSentTo("Cohost@x.com");
        assertEquals(1, toCohost.size(), "exactly one consent mail to the co-host");
        assertTrue(toCohost.getFirst().getHtml().contains("/consent/tok-123"), "carries the one-click consent link");
    }
}
