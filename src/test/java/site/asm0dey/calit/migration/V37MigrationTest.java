package site.asm0dey.calit.migration;

import module java.base;
import module java.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * V37 renames google_credential / google_calendar to calendar_account / calendar and leaves
 * compatibility views, paired booking / meeting_type columns and their triggers behind, so the
 * previous release keeps working during a rolling deploy. The previous-release tests at the bottom
 * pin that contract; delete them together with the shims (next release).
 */
@QuarkusTest
class V37MigrationTest {
    @Inject
    EntityManager em;
    @Inject
    DataSource dataSource;

    private Object one(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        for (var i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
        return q.getSingleResult();
    }

    private int exec(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        for (var i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
        return q.executeUpdate();
    }

    private long id(String sql, Object... params) {
        return ((Number) one(sql, params)).longValue();
    }

    private long googleAccount(String sub) {
        return id(
                "INSERT INTO google_credential (owner_id, refresh_token, google_sub, needs_reconnect) "
                + "VALUES (1, 'r', ?1, false) RETURNING id",
                sub
        );
    }

    private long meetingType(String slug) {
        return id(
                "INSERT INTO meeting_type (name, slug, duration_minutes, owner_id) VALUES ('M', ?1, 30, 1) RETURNING id",
                slug
        );
    }

    private long booking(String googleEventId) {
        var now = Instant.now();
        return id(
                "INSERT INTO booking (owner_id, meeting_type_id, invitee_name, invitee_email, start_utc, end_utc, "
                + "status, created_at, manage_token, google_event_id) "
                + "VALUES (1, ?1, 'I', 'i@example.com', ?2, ?3, 'CONFIRMED', ?2, ?4, ?5) RETURNING id",
                meetingType("v37-" + UUID.randomUUID()),
                now,
                now.plusSeconds(1800),
                UUID.randomUUID().toString(),
                googleEventId
        );
    }

    private List<Object> row(String sql, Object... params) {
        return Arrays.asList((Object[]) one(sql, params));
    }

    @Test
    @TestTransaction
    void insertThroughViewGetsGoogleProvider() {
        var id = googleAccount("sub1");
        assertThat(one("SELECT provider FROM calendar_account WHERE id = ?1", id)).isEqualTo("GOOGLE");
    }

    @Test
    @TestTransaction
    void caldavRowsInvisibleThroughView() {
        var id = id(
                "INSERT INTO calendar_account (owner_id, provider, caldav_server_url, caldav_username, caldav_password, "
                + "needs_reconnect) VALUES (1, 'CALDAV', 'https://dav.example.com', 'u', 'p', false) RETURNING id"
        );
        assertThat(((Number) one("SELECT count(*) FROM google_credential WHERE id = ?1", id)).longValue()).isZero();
    }

    @Test
    @TestTransaction
    void calendarViewExposesOldColumnNames() {
        var account = googleAccount("sub1");
        var id = id(
                "INSERT INTO google_calendar (owner_id, google_credential_id, google_calendar_id, summary, read_for_busy, "
                + "write_target, supports_meet) VALUES (1, ?1, 'cal@example.com', 'S', true, false, true) RETURNING id",
                account
        );
        assertThat(row("SELECT account_id, remote_id FROM calendar WHERE id = ?1", id))
            .containsExactly(account, "cal@example.com");
    }

    @Test
    @TestTransaction
    void googleCalendarViewIsUpdatable() {
        assertThat(one("SELECT is_updatable FROM information_schema.views WHERE table_name = 'google_calendar'"))
            .isEqualTo("YES");
        assertThat(one("SELECT is_updatable FROM information_schema.views WHERE table_name = 'google_credential'"))
            .isEqualTo("YES");
    }

    @Test
    @TestTransaction
    void lockingSelectThroughViewWorks() {
        googleAccount("sub1");
        assertThat(em
            .createNativeQuery(
                    "SELECT id FROM google_credential WHERE last_probed_at IS NULL FOR UPDATE SKIP LOCKED LIMIT 50"
            )
            .getResultList()
        )
            .hasSize(1);
    }

    @Test
    @TestTransaction
    void updateThroughViewWorks() {
        var id = googleAccount("sub1");
        exec("UPDATE google_credential SET needs_reconnect = true WHERE id = ?1", id);
        assertThat(one("SELECT needs_reconnect FROM calendar_account WHERE id = ?1", id)).isEqualTo(true);
    }

    @Test
    @TestTransaction
    void bookingOldColumnWriteMirrorsToNew() {
        var id = booking("e1");
        assertThat(one("SELECT event_id FROM booking WHERE id = ?1", id)).isEqualTo("e1");
        exec("UPDATE booking SET google_event_id = 'e2' WHERE id = ?1", id);
        assertThat(one("SELECT event_id FROM booking WHERE id = ?1", id)).isEqualTo("e2");
    }

    @Test
    @TestTransaction
    void bookingNewColumnWriteMirrorsToOld() {
        var id = booking(null);
        var account = googleAccount("sub1");
        exec(
                "UPDATE booking SET event_id = 'e3', calendar_account_id = ?1, calendar_remote_id = 'c1' WHERE id = ?2",
                account,
                id
        );
        assertThat(
                row("SELECT google_event_id, google_credential_id, google_calendar_id FROM booking WHERE id = ?1", id)
        )
            .containsExactly("e3", account, "c1");
        // and the old side of the account / calendar pairs mirrors to the new side
        var other = googleAccount("sub2");
        exec("UPDATE booking SET google_credential_id = ?1, google_calendar_id = 'c2' WHERE id = ?2", other, id);
        assertThat(row("SELECT calendar_account_id, calendar_remote_id FROM booking WHERE id = ?1", id))
            .containsExactly(other, "c2");
    }

    @Test
    @TestTransaction
    void bookingUnrelatedUpdateKeepsPair() {
        var id = booking(null);
        exec("UPDATE booking SET event_id = 'e3' WHERE id = ?1", id);
        // what the previous release's Hibernate full-row update does
        exec("UPDATE booking SET google_event_id = google_event_id, invitee_name = 'x' WHERE id = ?1", id);
        assertThat(row("SELECT event_id, google_event_id FROM booking WHERE id = ?1", id)).containsExactly("e3", "e3");
    }

    @Test
    @TestTransaction
    void accountDeleteNullsBothFks() {
        var id = booking(null);
        var account = googleAccount("sub1");
        exec("UPDATE booking SET calendar_account_id = ?1 WHERE id = ?2", account, id);
        exec("DELETE FROM calendar_account WHERE id = ?1", account);
        assertThat(row("SELECT google_credential_id, calendar_account_id FROM booking WHERE id = ?1", id))
            .containsExactly(null, null);
    }

    @Test
    @TestTransaction
    void meetingTypeOverridePairMirrors() {
        var mt = meetingType("v37-override");
        var a = googleAccount("sub1");
        var b = googleAccount("sub2");
        exec(
                "UPDATE meeting_type SET override_account_id = ?1, override_remote_calendar_id = 'n1' WHERE id = ?2",
                a,
                mt
        );
        assertThat(row("SELECT google_credential_id, google_calendar_id FROM meeting_type WHERE id = ?1", mt))
            .containsExactly(a, "n1");
        exec("UPDATE meeting_type SET google_credential_id = ?1, google_calendar_id = 'o1' WHERE id = ?2", b, mt);
        assertThat(row("SELECT override_account_id, override_remote_calendar_id FROM meeting_type WHERE id = ?1", mt))
            .containsExactly(b, "o1");
    }

    // --- the previous release's contract (delete with the shims, next release) ----------------------
    @Test
    void previousReleaseFlywayValidates(@TempDir Path dir) throws Exception {
        var source = Path.of(Thread.currentThread().getContextClassLoader().getResource("db/migration").toURI());
        try (var files = Files.list(source)) {
            for (Path f : files.toList()) {
                var m = Pattern.compile("V(\\d+)__.*\\.sql").matcher(f.getFileName().toString());
                if (m.matches() && Integer.parseInt(m.group(1)) <= 36) {
                    Files.copy(f, dir.resolve(f.getFileName()));
                }
            }
        }
        try (var files = Files.list(dir)) {
            assertThat(files.count()).isEqualTo(36);
        }
        var flyway = Flyway.configure().dataSource(dataSource).locations("filesystem:" + dir).load();
        assertThatCode(flyway::validate).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private List<String> columns(String table) {
        return ((List<Object[]>) em
            .createNativeQuery(
                    "SELECT column_name, data_type FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = ?1 ORDER BY column_name"
            )
            .setParameter(1, table)
            .getResultList())
            .stream()
            .map(r -> r[0] + " " + r[1])
            .toList();
    }

    @Test
    @TestTransaction
    void viewsPreserveOldColumnContract() {
        assertThat(columns("google_credential"))
            .containsExactly(
                    "access_token text",
                    "access_token_expiry timestamp with time zone",
                    "account_email character varying",
                    "google_sub character varying",
                    "id bigint",
                    "last_probed_at timestamp with time zone",
                    "needs_reconnect boolean",
                    "owner_id bigint",
                    "reconnect_notified_at timestamp with time zone",
                    "refresh_token text"
            );
        assertThat(columns("google_calendar"))
            .containsExactly(
                    "google_calendar_id character varying",
                    "google_credential_id bigint",
                    "id bigint",
                    "owner_id bigint",
                    "read_for_busy boolean",
                    "summary character varying",
                    "supports_meet boolean",
                    "write_target boolean"
            );
    }

    /**
     * Native SQL copied verbatim from origin/main (1.27.2) before Task 2 rewrote it: TokenBackfill,
     * GoogleConnectionScheduler (both claims) and PrivacyService's export. PersonalData on that
     * release holds column-name lists, not SQL, so there is nothing of it to replay.
     */
    @Test
    @TestTransaction
    void previousReleaseNativeSqlStillRuns() {
        var id = googleAccount("sub1");
        exec("UPDATE google_credential SET account_email = 'a@example.com' WHERE id = ?1", id);
        // TokenBackfill.encryptLegacy
        assertThat(em
            .createNativeQuery(
                    "select id, refresh_token, access_token from google_credential "
                    + "where refresh_token not like 'enc:v1:%' or access_token not like 'enc:v1:%' "
                    + "for update skip locked"
            )
            .getResultList()
        )
            .hasSize(1);
        em
            .createNativeQuery("update google_credential set refresh_token = :r, access_token = :a where id = :id")
            .setParameter("r", "enc:v1:r")
            .setParameter("a", "enc:v1:a")
            .setParameter("id", id)
            .executeUpdate();
        assertThat(row("SELECT refresh_token, access_token FROM calendar_account WHERE id = ?1", id))
            .containsExactly("enc:v1:r", "enc:v1:a");
        // GoogleConnectionScheduler.claimDueForProbe
        assertThat(em
            .createNativeQuery(
                    "SELECT id FROM google_credential "
                    + "WHERE last_probed_at IS NULL "
                    + "   OR last_probed_at <= now() - make_interval(secs => :secs) "
                    + "ORDER BY last_probed_at NULLS FIRST "
                    + "FOR UPDATE SKIP LOCKED "
                    + "LIMIT 50"
            )
            .setParameter("secs", 1800.0)
            .getResultList()
        )
            .hasSize(1);
        // GoogleConnectionScheduler.claimUnnotifiedDisconnects
        exec("UPDATE google_credential SET needs_reconnect = true WHERE id = ?1", id);
        assertThat(em
            .createNativeQuery(
                    "SELECT id FROM google_credential "
                    + "WHERE needs_reconnect = true AND reconnect_notified_at IS NULL "
                    + "FOR UPDATE SKIP LOCKED "
                    + "LIMIT 50"
            )
            .getResultList()
        )
            .hasSize(1);
        // PrivacyService export, the googleAccounts member
        assertThat(em
            .createNativeQuery(
                    """
                            SELECT coalesce((
                                SELECT json_agg(json_build_object(
                                    'email', coalesce(g.account_email, '')
                                ) ORDER BY g.id)
                                FROM google_credential g WHERE g.owner_id = :ownerId
                            ), '[]'::json)::text
                            """
            )
            .setParameter("ownerId", 1L)
            .getSingleResult()
        )
            .asString()
            .contains("a@example.com");
    }
}
