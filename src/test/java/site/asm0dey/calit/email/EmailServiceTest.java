package site.asm0dey.calit.email;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.BookingField.FieldType;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class EmailServiceTest {
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";
    @Inject
    EmailService emailService;
    @Inject
    MockMailbox mailbox;
    // Mock the Google connection state so we can drive the invitee-fallback branch
    // without a real OAuth connection.
    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        // Each test seeds a HELD (PENDING/CONFIRMED) booking at the same fixed start time; the DB
        // exclusion constraint (booking_no_overlap_held) rejects overlapping HELD rows. The plan's
        // seed commits its own transaction and never rolls back, so clear prior bookings first.
        QuarkusTransaction
            .requiringNew()
            .run(() -> Booking.deleteAll());
    }

    // ---- Confirmed + Google NOT connected: invitee + owner BOTH get mail ----
    @Test
    void confirmedWhenGoogleDisconnectedSendsToInviteeAndOwnerWithLocationManageLinkAnswersAndIcs() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/abc-defg-hij";
            b.answers = Map.of("description", "Pricing tiers", "company", "Acme");
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertThat(toInvitee).as("disconnected -> invitee fallback mail").hasSize(1);
        assertThat(toOwner).as("owner always (enabled)").hasSize(1);
        assertThat(mailbox.getTotalMessagesSent()).isEqualTo(2);

        Mail m = toInvitee.getFirst();
        assertThat(m.getHtml()).as("meeting type name").contains("Discovery Call");
        // location present (Meet link, GOOGLE_MEET type)
        assertThat(m.getHtml()).as("location/meet link").contains("https://meet.google.com/abc-defg-hij");
        // manage link from manageToken
        assertThat(m.getHtml()).as("manage link path present").contains("/booking/");
        assertThat(m.getHtml()).as("manage link suffix present").contains("/manage");
        // answers
        assertThat(m.getHtml()).as("field label").contains("What do you want to discuss?");
        assertThat(m.getHtml()).as("answer value").contains("Pricing tiers");
        assertThat(m.getHtml()).contains("Company");
        assertThat(m.getHtml()).contains("Acme");
        assertThat(m.getSubject().toLowerCase()).contains("confirmed");
        // .ics attachment present on an app-sent mail
        assertHasIcsAttachment(m);
    }

    // ---- Confirmed + Google connected: invitee + owner both get link email, but NO .ics ----
    @Test
    void confirmedWhenGoogleConnectedSendsLinkEmailToInviteeAndOwnerWithoutIcs() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/xyz";
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL)).as("connected -> invitee still gets calit link email").hasSize(
                1
        );
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).as("owner still gets the app mail").hasSize(1);
        assertThat(mailbox.getTotalMessagesSent()).isEqualTo(2);
        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst().getAttachments())
            .as("no .ics when Google notifies")
            .isEmpty();
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL).getFirst().getAttachments())
            .as("no .ics when Google notifies")
            .isEmpty();
    }

    // ---- BookingRequested (PENDING): always to invitee + owner, regardless of Google ----
    @Test
    void requestedAlwaysSendsToInviteeAndOwnerEvenWhenGoogleConnected() {
        // connected, but no Google event exists yet
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seed(b -> {
            b.status = BookingStatus.PENDING;
            b.meetLink = null;
            b.answers = Map.of("description", "Need a demo");
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleRequested(new BookingRequested(bookingId));

        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL)).as("invitee always gets the request notice").hasSize(1);
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).hasSize(1);
        assertThat(mailbox.getTotalMessagesSent()).isEqualTo(2);
        Mail m = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(m.getSubject().toLowerCase()).contains("request");
        assertThat(m.getHtml()).contains("Need a demo");
        // No .ics when Google is connected (Google notifies natively)
        assertThat(m.getAttachments()).as("no .ics when Google connected").isEmpty();
    }

    // ---- BookingDeclined: always to invitee, regardless of Google ----
    @Test
    void declinedAlwaysSendsToInviteeEvenWhenGoogleConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seed(b -> {
            b.status = BookingStatus.DECLINED;
            b.meetLink = null;
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleDeclined(new BookingDeclined(bookingId));

        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL))
            .as("declined is an always-send exception (no Google event)")
            .hasSize(1);
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).hasSize(1);
        Mail m = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(m.getSubject().toLowerCase()).contains("declin");
        assertThat(m.getAttachments()).as("no .ics when Google connected (no calendar event was ever created)").isEmpty();
    }

    // ---- ownerNotificationsEnabled = false: owner gets nothing; invitee per rules ----
    @Test
    void ownerOptedOutGetsNothingInviteeStillFallbackWhenDisconnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/opt-out";
        }, false, /* ownerNotificationsEnabled = false */
        LocationType.GOOGLE_MEET, null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).as("owner opted out -> no owner mail").isEmpty();
        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL)).as("invitee still gets fallback (disconnected)").hasSize(1);
        assertThat(mailbox.getTotalMessagesSent()).isOne();
    }

    @Test
    void ownerOptedOutAndGoogleConnectedSendsOnlyLinkEmailToInvitee() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/none";
        }, false, LocationType.GOOGLE_MEET, null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).as("owner opted out -> no owner mail").isEmpty();
        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL)).as("invitee always gets the calit link email").hasSize(1);
        assertThat(mailbox.getTotalMessagesSent()).isOne();
        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst().getAttachments())
            .as("no .ics when Google connected")
            .isEmpty();
    }

    // ---- Non-Meet location (PHONE) renders locationDetail, not a link ----
    @Test
    void confirmedPhoneLocationRendersLocationDetail() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = null;
        }, true, LocationType.PHONE, "+1 555 0100");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail m = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(m.getHtml()).as("phone locationDetail rendered").contains("+1 555 0100");
        assertThat(m.getHtml()).as("no meet link for PHONE type").doesNotContain("meet.google.com");
    }

    // ---- Reschedule when connected: invitee + owner both get a link email, but NO .ics ----
    @Test
    void rescheduleWhenConnectedSendsLinkEmailToInviteeAndOwnerWithoutIcs() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        var newStart = Instant.parse("2026-06-10T09:00:00Z");
        long bookingId = seedAt(newStart, b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/resch";
        }, true, LocationType.GOOGLE_MEET, null);
        var oldStart = Instant.parse("2026-06-08T09:00:00Z");

        emailService.handleRescheduled(new BookingRescheduled(bookingId, oldStart, false));

        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL)).as("connected -> invitee still gets calit link email").hasSize(
                1
        );
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL).getFirst().getSubject().toLowerCase()).contains("reschedul");
        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst().getAttachments())
            .as("no .ics when Google notifies")
            .isEmpty();
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL).getFirst().getAttachments())
            .as("no .ics when Google notifies")
            .isEmpty();
    }

    // ---- Reschedule attribution: who moved it drives the wording, not who receives ----
    @Test
    void hostRescheduleNamesHostToGuestAndDoesNotBlameGuestToOwner() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var newStart = Instant.parse("2026-06-10T09:00:00Z");
        long bookingId = seedAt(newStart, b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");
        var oldStart = Instant.parse("2026-06-08T09:00:00Z");

        emailService.handleRescheduled(new BookingRescheduled(bookingId, oldStart, true));

        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(invitee.getHtml())
            .as("host-initiated: invitee copy names the host")
            .contains("Owner rescheduled your booking");
        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertThat(owner.getHtml())
            .as("host-initiated: owner copy must not blame the guest")
            .doesNotContain("Sam Invitee rescheduled");
    }

    @Test
    void guestRescheduleNamesGuestToOwnerAndStaysPassiveToGuest() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var newStart = Instant.parse("2026-06-10T09:00:00Z");
        long bookingId = seedAt(newStart, b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");
        var oldStart = Instant.parse("2026-06-08T09:00:00Z");

        emailService.handleRescheduled(new BookingRescheduled(bookingId, oldStart, false));

        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertThat(owner.getHtml())
            .as("guest-initiated: owner copy names the guest")
            .contains("Sam Invitee rescheduled their booking");
        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(invitee.getHtml())
            .as("guest-initiated: invitee copy stays passive")
            .doesNotContain("rescheduled your booking");
    }

    // ---- Cancellation: fallback rule, no location/meet link in body ----
    @Test
    void cancellationWhenDisconnectedSendsToBothWithoutMeetLink() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CANCELLED;
            b.meetLink = "https://meet.google.com/will-not-appear";
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleCancelled(new BookingCancelled(bookingId, false));

        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).hasSize(1);
        Mail m = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(m.getSubject().toLowerCase()).contains("cancel");
        assertThat(m.getHtml()).as("cancellation body must not include a meet link").doesNotContain("will-not-appear");
    }

    @Test
    void hostCancelNamesHostToGuestAndSaysTheHostActedToOwner() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CANCELLED, true, LocationType.PHONE, "+1");

        emailService.handleCancelled(new BookingCancelled(bookingId, true));

        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(invitee.getHtml())
            .as("host-initiated: invitee copy names the host")
            .contains("Owner cancelled your booking");
        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertThat(owner.getHtml())
            .as("host-initiated: owner copy says the host acted and names the guest")
            .contains("You cancelled your meeting with Sam Invitee.");
        assertThat(owner.getHtml())
            .as("host-initiated: owner copy must not reuse the invitee's passive string")
            .doesNotContain("Your booking has been cancelled.");
    }

    @Test
    void groupHostCancelDoesNotTellTheNonActingCohostTheyCancelled() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var creatorBookingId = seedGroup()[0];
        // The creator (owner id 1) initiates the cancel; byOwner=true fans out to every accepted
        // host's own row via hostDeliveries -- including the co-host, who did not click cancel.
        emailService.handleCancelled(new BookingCancelled(creatorBookingId, true));

        Mail cohostMail = mailbox.getMailsSentTo("volodya@x.com").getFirst();
        assertThat(cohostMail.getHtml())
            .as("non-acting co-host must not be told they personally cancelled")
            .doesNotContain("You cancelled");
        assertThat(cohostMail.getHtml())
            .as("non-acting co-host falls back to the same passive body group bookings used before this fix")
            .contains("Your booking has been cancelled.");
        // The single-host case (already covered elsewhere) keeps the active first-person body; a
        // group booking's OWN acting host also gets the passive fallback -- hostSelfCancel is keyed
        // purely on groupId == null, matching the reviewer's prescribed narrowing.
        Mail creatorMail = mailbox.getMailsSentTo("pasha@x.com").getFirst();
        assertThat(creatorMail.getHtml())
            .as("group booking never uses the first-person self-cancel body, even for the acting host")
            .doesNotContain("You cancelled");
    }

    @Test
    void guestCancelNamesGuestToOwnerAndStaysPassiveToGuest() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CANCELLED, true, LocationType.PHONE, "+1");

        emailService.handleCancelled(new BookingCancelled(bookingId, false));

        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertThat(owner.getHtml())
            .as("guest-initiated: owner copy names the guest as the actor")
            .contains("Sam Invitee cancelled their booking.");
        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(invitee.getHtml())
            .as("guest-initiated: invitee copy stays passive — it happened to them")
            .contains("Your booking has been cancelled.");
    }

    // ---- Reminder follows the fallback rule ----
    @Test
    void reminderWhenDisconnectedSendsToBoth() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/rem";
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleReminder(new ReminderDue(bookingId));

        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst().getSubject().toLowerCase()).contains("reminder");
    }

    // ---- From header carries owner display name for booking mail ----
    @Test
    void bookingMailFromCarriesOwnerDisplayName() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertThat(owner.getFrom()).isEqualTo("Owner via calit <calit@example.com>");
    }

    @Test
    void confirmedOwnerMailContainsManageLink() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertThat(owner.getHtml())
            .as("owner copy links to the /me manage page")
            .contains("/me/bookings/" + bookingId + "/manage");
        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertThat(invitee.getHtml()).as("invitee copy must NOT contain the owner /me link").doesNotContain(
                "/me/bookings/"
        );
    }

    @Test
    void fromNameStripsInjectedCrLfFromOwnerName() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");
        // Overwrite ownerName with a header-injection payload
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.ownerName = "Evil\r\nBcc: evil@example.com";
            s.persist();
        });

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        String from = owner.getFrom();
        assertThat(from.contains("\r") || from.contains("\n"))
            .as("From header must not contain CR or LF; got: " + from)
            .isFalse();
    }

    @Test
    void passwordResetMailHasNoPerMessageFrom() {
        mailbox.clear();
        emailService.sendPasswordReset(
                1L,
                "u@example.com",
                "https://x/reset",
                Instant.now().plusSeconds(3600),
                java.util.Locale.ENGLISH
        );
        assertThat(mailbox.getMailsSentTo("u@example.com").getFirst().getFrom())
            .as("no per-message From -> falls back to config default")
            .isNull();
    }

    @Test
    void inviteeGetsLinkEmailWithoutIcsWhenGoogleConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/link-test";
        }, true, LocationType.GOOGLE_MEET, null);
        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertThat(toInvitee).as("invitee still gets the calit notice (carries the manage link)").hasSize(1);
        Mail m = toInvitee.getFirst();
        assertThat(m.getHtml()).as("manage/reschedule link present").contains("/manage");
        assertThat(m.getAttachments()).as("no .ics when Google notifies").isEmpty();
    }

    // --- attachment assertion: every app-sent mail carries an .ics ---
    private static void assertHasIcsAttachment(Mail m) {
        assertThat(m.getAttachments()).as("mail must carry an attachment").isNotEmpty();
        assertThat(m
            .getAttachments()
            .stream()
            .anyMatch(a -> "invite.ics".equals(a.getName())
                    || (a.getContentType() != null && a.getContentType().contains("text/calendar")))
        )
            .as("an .ics (text/calendar) attachment must be present")
            .isTrue();
    }

    @Test
    void confirmedOwnerCopyCarriesTheInviteeAddressButTheInviteeCopyDoesNot() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.CUSTOM, "Room 1");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        String ownerHtml = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst().getHtml();
        assertThat(ownerHtml).as("owner can click through to the invitee").contains("mailto:" + INVITEE_EMAIL);
        assertThat(ownerHtml).as("owner copy labels the line").contains("Invitee:");

        String inviteeHtml = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst().getHtml();
        assertThat(inviteeHtml).as("invitee copy is unchanged").doesNotContain("mailto:" + INVITEE_EMAIL);
    }

    // --- seeding helpers ---
    /**
     * A two-host group booking (admin/owner id 1 as creator "pasha" + a second accepted co-host
     * "volodya"), sharing one {@code groupId} across two {@link Booking} rows -- one per host --
     * already {@code CANCELLED}. Mirrors {@code MultiHostFixtures.acceptedTwoHostType} /
     * {@code GroupCancelRescheduleTest}'s fixture pattern. Returns {@code [creatorBookingId,
     * cohostBookingId]}.
     */
    private long[] seedGroup() {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser cohost = MultiHostFixtures.enabledUser("volodya");
            MultiHostFixtures.settings(1L, "pasha");
            MultiHostFixtures.settings(cohost.id, "volodya");

            MeetingType type = MultiHostFixtures.acceptedTwoHostType(1L, cohost.id, "intro", 30, false);

            var groupId = UUID.randomUUID();
            var startUtc = Instant.parse("2026-06-08T09:00:00Z");
            long creatorBookingId = groupRow(groupId, 1L, type.id, startUtc);
            long cohostBookingId = groupRow(groupId, cohost.id, type.id, startUtc);
            return new long[] {creatorBookingId, cohostBookingId};
        });
    }

    private static long groupRow(UUID groupId, long ownerId, long meetingTypeId, Instant startUtc) {
        Booking b = new Booking();
        b.ownerId = ownerId;
        b.meetingTypeId = meetingTypeId;
        b.groupId = groupId;
        b.inviteeName = "Sam Invitee";
        b.inviteeEmail = INVITEE_EMAIL;
        b.startUtc = startUtc;
        b.endUtc = startUtc.plus(30, ChronoUnit.MINUTES);
        b.status = BookingStatus.CANCELLED;
        b.answers = Map.of();
        b.manageToken = "tok-" + ownerId + "-" + System.nanoTime();
        b.createdAt = Instant.now();
        b.persist();
        return b.id;
    }

    private long seed(
            java.util.function.Consumer<Booking> tweak,
            boolean ownerNotificationsEnabled,
            LocationType locationType,
            String locationDetail
    ) {
        return seedAt(
                Instant.parse("2026-06-08T09:00:00Z"),
                tweak,
                ownerNotificationsEnabled,
                locationType,
                locationDetail
        );
    }

    private long seedAt(
            Instant startUtc,
            java.util.function.Consumer<Booking> tweak,
            boolean ownerNotificationsEnabled,
            LocationType locationType,
            String locationDetail
    ) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                if (s == null) {
                    s = new OwnerSettings();
                    s.ownerId = 1L;
                }
                s.ownerName = "Owner";
                s.ownerEmail = OWNER_EMAIL;
                s.timezone = "Europe/Amsterdam";
                s.ownerNotificationsEnabled = ownerNotificationsEnabled;
                s.persist();

                MeetingType t = new MeetingType();
                t.ownerId = 1L;
                t.name = "Discovery Call";
                t.slug = "discovery-" + System.nanoTime();
                t.durationMinutes = 30;
                t.locationType = locationType;
                t.locationDetail = locationDetail;
                t.persist();
                // Global custom fields so answers render with labels in order.
                BookingField f1 = new BookingField();
                f1.ownerId = 1L;
                f1.meetingTypeId = null;
                f1.fieldKey = "description";
                f1.label = "What do you want to discuss?";
                f1.type = FieldType.LONG_TEXT;
                f1.required = false;
                f1.position = 0;
                f1.persist();

                BookingField f2 = new BookingField();
                f2.ownerId = 1L;
                f2.meetingTypeId = null;
                f2.fieldKey = "company";
                f2.label = "Company";
                f2.type = FieldType.SHORT_TEXT;
                f2.required = false;
                f2.position = 1;
                f2.persist();

                Booking b = new Booking();
                b.ownerId = 1L;
                b.meetingTypeId = t.id;
                b.inviteeName = "Sam Invitee";
                b.inviteeEmail = INVITEE_EMAIL;
                b.startUtc = startUtc;
                b.endUtc = startUtc.plus(30, ChronoUnit.MINUTES);
                b.googleEventId = "evt-" + System.nanoTime();
                b.meetLink = null;
                b.status = BookingStatus.CONFIRMED;
                b.answers = Map.of();
                b.manageToken = "tok-" + System.nanoTime();
                b.createdAt = Instant.now();
                tweak.accept(b);
                b.persist();
                return b.id;
            });
    }
}
