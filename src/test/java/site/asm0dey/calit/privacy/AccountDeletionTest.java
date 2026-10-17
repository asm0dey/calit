package site.asm0dey.calit.privacy;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.caldav.CaldavCalendarPort;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarProvider;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.email.MailTag;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.LoginTicket;
import site.asm0dey.calit.user.PasswordResetToken;

@QuarkusTest
class AccountDeletionTest {
    @Inject
    PrivacyService privacy;
    @Inject
    EntityManager em;
    @InjectMock
    CaldavCalendarPort caldav;

    @BeforeEach
    void stubCaldav() {
        when(caldav.provider()).thenReturn(CalendarProvider.CALDAV);
        when(caldav.isConnected(anyLong())).thenReturn(true);
    }

    /**
     * A second, non-admin account with a settings row, so deleting it is legal.
     */
    private Long seedSecondUser() {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.create("deletable", "x", false);
            u.persist();
            site.asm0dey.calit.domain.OwnerSettings.seed(u.id, "deletable@example.com");
            EmailOutbox.enqueue("deletable@example.com", "s", "<p>hi</p>", null, null, "seed", MailTag.forOwner(u.id));
            return u.id;
        });
    }

    private long rowsFor(String table, String column, Long value) {
        return ((Number) em
            .createNativeQuery("select count(*) from " + table + " where " + column + " = :v")
            .setParameter("v", value)
            .getSingleResult())
            .longValue();
    }

    @Test
    void deletionReachesEveryOwnerScopedTable() {
        var id = seedSecondUser();
        privacy.deleteAccount(id);

        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(AppUser.count("id", id)).as("the app_user row is gone").isZero();
            for (PersonalData.Classified c : PersonalData.TABLES) {
                if (c.columns().contains("owner_id")) {
                    assertThat(rowsFor(c.table(), "owner_id", id)).as(c.table()
                            + " must not survive account deletion").isZero();
                }
            }
        });
    }

    @Test
    void deletionClearsParkedMailForThatOwner() {
        var id = seedSecondUser();
        privacy.deleteAccount(id);
        QuarkusTransaction
            .requiringNew()
            .run(() -> assertThat(EmailOutbox.count("ownerId", id)).isZero());
    }

    @Test
    void theLastEnabledAdminCannotBeDeleted() {
        // DatabaseResetCallback seeds exactly one admin, always id 1.
        assertThat(privacy.isLastEnabledAdmin(1L)).isTrue();
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> privacy.deleteAccount(1L));
        QuarkusTransaction
            .requiringNew()
            .run(() -> assertThat(AppUser.count("id", 1L)).isOne());
    }

    /**
     * Every table keyed by {@code user_id} rather than {@code owner_id} ({@link PersonalData#TABLES}
     * only walks {@code owner_id} columns) also cascades from {@code app_user}: password reset
     * tokens and login tickets.
     */
    @Test
    void deletionReachesUserIdKeyedTables() {
        var id = seedSecondUser();
        QuarkusTransaction.requiringNew().run(() -> {
            var reset = new PasswordResetToken();
            reset.userId = id;
            reset.tokenHash = "reset-" + UUID.randomUUID();
            reset.expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            reset.persist();

            var ticket = new LoginTicket();
            ticket.userId = id;
            ticket.tokenHash = "ticket-" + UUID.randomUUID();
            ticket.expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            ticket.persist();
        });

        privacy.deleteAccount(id);

        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(rowsFor("password_reset_token", "user_id", id)).isZero();
            assertThat(rowsFor("login_ticket", "user_id", id)).isZero();
        });
    }

    /**
     * A user who co-hosts ANOTHER owner's meeting type is deletable without failing on the {@code
     * meeting_type_host.owner_id -> app_user} FK, and the cascade removes only the co-host's own row
     * — the other owner's meeting type (and its own CREATOR-side data) survives untouched.
     */
    @Test
    void deletingACohostDoesNotFailOrTouchTheOtherOwnersMeetingType() {
        Long creatorId = QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.create("host-owner", "x", false);
            u.persist();
            site.asm0dey.calit.domain.OwnerSettings.seed(u.id, "host-owner@example.com");
            return u.id;
        });
        Long typeId = QuarkusTransaction.requiringNew().call(() -> {
            var t = new MeetingType();
            t.ownerId = creatorId;
            t.name = "Shared type";
            t.slug = "shared-type-" + UUID.randomUUID();
            t.durationMinutes = 30;
            t.persist();
            return t.id;
        });
        var cohostId = seedSecondUser();
        QuarkusTransaction
            .requiringNew()
            .run(() -> MeetingTypeHost
                .of(typeId, cohostId, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)
                .persist());

        privacy.deleteAccount(cohostId);

        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(AppUser.count("id", cohostId)).as("the co-host's own app_user row is gone").isZero();
            assertThat(MeetingType.<MeetingType>findById(typeId)).as("the other owner's meeting type survives").isNotNull();
            assertThat(AppUser.count("id", creatorId)).as("the other owner's account survives").isOne();
            assertThat(MeetingTypeHost.count("meetingTypeId = ?1 and ownerId = ?2", typeId, cohostId))
                .as("the deleted co-host's own host row is gone")
                .isZero();
        });
    }

    @Test
    void deletionRemovesCaldavAccountAndDeletesItsMirrorEventThroughTheRouter() {
        var id = seedSecondUser();
        QuarkusTransaction.requiringNew().run(() -> {
            var a = new CaldavAccount();
            a.ownerId = id;
            a.serverUrl = "https://dav.example.com/";
            a.username = "alice";
            a.password = "secret";
            a.persist();
            var t = new MeetingType();
            t.ownerId = id;
            t.name = "Dav type";
            t.slug = "dav-type-" + UUID.randomUUID();
            t.durationMinutes = 30;
            t.persist();
            var b = new Booking();
            b.ownerId = id;
            b.meetingTypeId = t.id;
            b.inviteeName = "Dana";
            b.inviteeEmail = "dana@example.com";
            b.startUtc = Instant.now().plus(10, ChronoUnit.DAYS);
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.eventId = "evt-dav";
            b.calendarAccountId = a.id;
            b.calendarRemoteId = "/cal/work/";
            b.persist();
        });

        privacy.deleteAccount(id);

        verify(caldav).deleteEvent(eq(id), org.mockito.ArgumentMatchers.any(), eq("evt-dav"));
        QuarkusTransaction
            .requiringNew()
            .run(() -> assertThat(rowsFor("calendar_account", "owner_id", id)).isZero());
    }
}
