package site.asm0dey.calit.migration;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

/**
 * V34 adds the privacy columns and makes booking -> meeting_type cascade explicit.
 */
@QuarkusTest
class V34MigrationTest {
    @Inject
    EntityManager em;

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private long column(String table, String col) {
        return scalar(
                "select count(*) from information_schema.columns where table_name='" + table + "' and column_name='" + col + "'"
        );
    }

    @Test
    @Transactional
    void privacyColumnsExist() {
        assertThat(column("booking", "erased_at")).as("booking.erased_at must exist").isOne();
        assertThat(column("owner_settings", "booking_retention_days"))
            .as("owner_settings.booking_retention_days must exist")
            .isOne();
        assertThat(column("email_outbox", "booking_id")).as("email_outbox.booking_id must exist").isOne();
        assertThat(column("email_outbox", "owner_id")).as("email_outbox.owner_id must exist").isOne();
    }

    @Test
    @Transactional
    void newColumnsAreNullable() {
        // Paired by (table, column), not a loose cross-product: 'owner_id' also names the
        // pre-existing, correctly NOT NULL booking.owner_id / owner_settings.owner_id columns,
        // which are unrelated to V34 and must not be swept in by a bare column_name IN (...).
        var notNullable = scalar(
                "select count(*) from information_schema.columns "
                + "where (table_name='booking' and column_name='erased_at' and is_nullable='NO') "
                + "or (table_name='owner_settings' and column_name='booking_retention_days' and is_nullable='NO') "
                + "or (table_name='email_outbox' and column_name='booking_id' and is_nullable='NO') "
                + "or (table_name='email_outbox' and column_name='owner_id' and is_nullable='NO')"
        );
        assertThat(notNullable).as("every V34 column must be nullable — no backfill").isZero();
    }

    @Test
    @Transactional
    void bookingMeetingTypeCascadesExplicitly() {
        var cascade = scalar(
                "select count(*) from information_schema.referential_constraints rc "
                + "join information_schema.key_column_usage k on k.constraint_name = rc.constraint_name "
                + "where k.table_name='booking' and k.column_name='meeting_type_id' "
                + "and rc.delete_rule='CASCADE'"
        );
        assertThat(cascade).as("booking.meeting_type_id must declare ON DELETE CASCADE").isOne();
    }

    @Test
    @Transactional
    void outboxLinksCascade() {
        var cascades = scalar(
                "select count(*) from information_schema.referential_constraints rc "
                + "join information_schema.key_column_usage k on k.constraint_name = rc.constraint_name "
                + "where k.table_name='email_outbox' and k.column_name in ('booking_id','owner_id') "
                + "and rc.delete_rule='CASCADE'"
        );
        assertThat(cascades).as("both email_outbox links must cascade").isEqualTo(2L);
    }
}
