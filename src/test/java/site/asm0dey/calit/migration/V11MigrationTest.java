package site.asm0dey.calit.migration;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class V11MigrationTest {
    @Inject
    EntityManager em;

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    @Test
    @Transactional
    void passwordHashBecomesNullableAndGoogleSubExists() {
        var notNullable = scalar(
                "select count(*) from information_schema.columns "
                + "where table_name='app_user' and column_name='password_hash' and is_nullable='NO'"
        );
        assertThat(notNullable).as("password_hash must be nullable after V11").isZero();

        var sub = scalar(
                "select count(*) from information_schema.columns " + "where table_name='app_user' and column_name='google_sub'"
        );
        assertThat(sub).as("app_user.google_sub must exist").isOne();
    }

    @Test
    @Transactional
    void loginTicketTableExists() {
        var table = scalar("select count(*) from information_schema.tables where table_name='login_ticket'");
        assertThat(table).as("login_ticket table must exist").isOne();
        assertThat(scalar("select count(*) from login_ticket") >= 0).as("login_ticket must be queryable").isTrue();
    }
}
