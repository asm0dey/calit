package site.asm0dey.calit.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.email.MailSender;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Account deletion cancels the upcoming bookings on the account's own meeting types (the {@code
 * meeting_type_id} cascade would otherwise remove them silently, co-hosts' rows included), and
 * mail parked for the deleted owner goes with the account while cancellation notices owed to
 * other people survive it.
 */
@QuarkusTest
class AccountDeletionNotifiesTest {

    private static final String INVITEE = "ivy@example.com";

    @Inject
    PrivacyService privacy;

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectSpy
    MailSender mailSender;

    @BeforeEach
    void clearMailbox() {
        mailbox.clear();
    }

    /** A non-admin creator and a co-host sharing one type, plus an upcoming group booking on it. */
    private record Shared(Long creatorId, Long cohostId, UUID groupId) {}

    private static Shared seedSharedTypeWithUpcomingGroup() {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser creator = MultiHostFixtures.enabledUser("shared-creator");
            AppUser cohost = MultiHostFixtures.enabledUser("shared-cohost");
            MultiHostFixtures.settings(creator.id, "shared-creator");
            MultiHostFixtures.settings(cohost.id, "shared-cohost");
            MeetingType type = MultiHostFixtures.acceptedTwoHostType(creator.id, cohost.id, "shared-del", 30, false);
            var groupId = UUID.randomUUID();
            var start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
            for (Long owner : List.of(creator.id, cohost.id)) {
                var b = new Booking();
                b.ownerId = owner;
                b.meetingTypeId = type.id;
                b.groupId = groupId;
                b.inviteeName = "Ivy";
                b.inviteeEmail = INVITEE;
                b.locale = "en";
                b.startUtc = start;
                b.endUtc = start.plus(30, ChronoUnit.MINUTES);
                b.status = BookingStatus.CONFIRMED;
                b.createdAt = Instant.now();
                b.manageToken = UUID.randomUUID().toString();
                b.persist();
            }
            return new Shared(creator.id, cohost.id, groupId);
        });
    }

    private static long groupRows(UUID groupId) {
        return QuarkusTransaction.requiringNew().call(() -> Booking.count("groupId", groupId));
    }

    @Test
    void deletingTheCreatorCancelsTheGroupAndMailsTheInviteeAndCohost() {
        var s = seedSharedTypeWithUpcomingGroup();

        privacy.deleteAccount(s.creatorId());

        QuarkusTransaction.requiringNew()
                .run(() -> assertEquals(0L, AppUser.count("id", s.creatorId()), "the account is gone"));
        assertEquals(0L, groupRows(s.groupId()), "the type's bookings cascade away after the cancel");
        assertEquals(
                1,
                QuarkusTransaction.requiringNew().call(() -> (int) AppUser.count("id", s.cohostId())),
                "the co-host's account survives");
        assertFalse(mailbox.getMailsSentTo(INVITEE).isEmpty(), "the invitee is told the meeting is cancelled");
        assertFalse(
                mailbox.getMailsSentTo("shared-cohost@x.com").isEmpty(),
                "the co-host is told the meeting is cancelled");
        assertTrue(
                mailbox.getMailsSentTo(INVITEE)
                        .getFirst()
                        .getSubject()
                        .toLowerCase(Locale.ROOT)
                        .contains("cancel"),
                "the invitee's mail is the cancellation notice");
    }

    /**
     * SMTP down: the cancellation notices are parked. The invitee's and co-host's copies must
     * outlive the deleted account (their booking link is dropped, so neither the owner purge nor
     * the booking cascade takes them); the deleted owner's own copy is purged with the account.
     */
    @Test
    void parkedCancellationNoticesForOtherPeopleSurviveTheDeletion() {
        var s = seedSharedTypeWithUpcomingGroup();
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender)
                .sendNow(any(), anyString(), anyString(), anyString(), any());

        privacy.deleteAccount(s.creatorId());

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(0L, EmailOutbox.count("ownerId", s.creatorId()), "nothing stays tagged to the deleted owner");
            assertEquals(0L, EmailOutbox.count("recipient", "shared-creator@x.com"), "the owner's own copy is purged");
            assertEquals(1L, EmailOutbox.count("recipient", INVITEE), "the invitee's notice is still queued");
            EmailOutbox cohostCopy =
                    EmailOutbox.find("recipient", "shared-cohost@x.com").firstResult();
            assertEquals(s.cohostId(), cohostCopy.ownerId, "the co-host's copy stays tagged to the co-host");
            assertNull(cohostCopy.bookingId, "the booking link is dropped before the cascade");
        });
    }

    @Test
    void parkedResetAndInviteMailForTheOwnerIsRemoved() {
        Long id = QuarkusTransaction.requiringNew().call(() -> MultiHostFixtures.enabledUser("parked-mail-owner").id);
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender)
                .sendNow(any(), anyString(), anyString(), anyString(), any());
        var expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        emailService.sendPasswordReset(id, "parked@example.com", "https://x/reset", expiry, Locale.ENGLISH);
        emailService.sendInvite(
                id, "parked@example.com", "https://x/activate", "admin", "https://x", expiry, Locale.ENGLISH);
        emailService.sendGoogleDisconnected(id, "parked@example.com", "work@gmail.com", Locale.ENGLISH);
        assertEquals(
                3L,
                QuarkusTransaction.requiringNew().call(() -> EmailOutbox.count("ownerId", id)),
                "precondition: all three owner mails are parked and tagged with the owner");

        privacy.deleteAccount(id);

        assertEquals(
                0L, QuarkusTransaction.requiringNew().call(() -> EmailOutbox.count("recipient", "parked@example.com")));
    }
}
