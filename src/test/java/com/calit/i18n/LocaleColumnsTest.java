package com.calit.i18n;

import com.calit.domain.OwnerSettings;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class LocaleColumnsTest {

    @Inject
    EntityManager em;

    @Test
    @TestTransaction
    void ownerSettingsHasLocaleDefaultingToEn() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
            s.ownerName = "Admin";
            s.ownerEmail = "admin@example.com";
            s.timezone = "UTC";
        }
        s.persist();

        OwnerSettings loaded = OwnerSettings.forOwner(1L); // admin always id 1
        assertEquals("en", loaded.locale);
    }

    /**
     * Proves the DB column DEFAULT 'en' on owner_settings is active:
     * inserts a row via native SQL deliberately omitting the locale column,
     * then reads it back and asserts the DB supplied 'en'.
     */
    @Test
    @TestTransaction
    void dbDefaultLocaleForOwnerSettings() {
        // owner_settings.id is BIGINT (not BIGSERIAL) so we supply it.
        // owner_id=1 references the admin user always seeded by DatabaseResetCallback.
        em.createNativeQuery(
                "INSERT INTO owner_settings (id, owner_id, owner_name, owner_email, timezone) " +
                "VALUES (9001, 1, 'Test Owner', 'test@example.com', 'UTC')"
        ).executeUpdate();

        em.flush();
        em.clear();

        String locale = (String) em.createNativeQuery(
                "SELECT locale FROM owner_settings WHERE id = 9001"
        ).getSingleResult();

        assertEquals("en", locale, "DB DEFAULT 'en' must be applied when locale is omitted from INSERT");
    }

    /**
     * Proves the DB column DEFAULT 'en' on booking is active:
     * inserts a meeting_type and a booking via native SQL deliberately omitting
     * the locale column, then reads it back and asserts the DB supplied 'en'.
     */
    @Test
    @TestTransaction
    void dbDefaultLocaleForBooking() {
        // Insert a minimal meeting_type to satisfy the booking FK.
        // owner_id=1 references the admin user always seeded by DatabaseResetCallback.
        em.createNativeQuery(
                "INSERT INTO meeting_type (name, slug, duration_minutes, owner_id) " +
                "VALUES ('Test Meeting', 'test-slug-locale', 30, 1)"
        ).executeUpdate();

        Number mtId = (Number) em.createNativeQuery(
                "SELECT id FROM meeting_type WHERE slug = 'test-slug-locale'"
        ).getSingleResult();

        String manageToken = UUID.randomUUID().toString();
        Instant now = Instant.now();

        // Insert booking omitting locale — the DB DEFAULT 'en' should fill it in.
        em.createNativeQuery(
                "INSERT INTO booking " +
                "(owner_id, meeting_type_id, invitee_name, invitee_email, " +
                " start_utc, end_utc, status, created_at, manage_token) " +
                "VALUES (1, :mtId, 'Test Invitee', 'invitee@example.com', " +
                " :start, :end, 'CONFIRMED', :created, :token)"
        )
                .setParameter("mtId", mtId.longValue())
                .setParameter("start", now)
                .setParameter("end", now.plusSeconds(1800))
                .setParameter("created", now)
                .setParameter("token", manageToken)
                .executeUpdate();

        em.flush();
        em.clear();

        String locale = (String) em.createNativeQuery(
                "SELECT locale FROM booking WHERE manage_token = :token"
        )
                .setParameter("token", manageToken)
                .getSingleResult();

        assertEquals("en", locale, "DB DEFAULT 'en' must be applied when locale is omitted from booking INSERT");
    }
}
