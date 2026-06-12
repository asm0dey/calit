package com.calit.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        long notNullable = scalar(
                "select count(*) from information_schema.columns " +
                "where table_name='app_user' and column_name='password_hash' and is_nullable='NO'");
        assertEquals(0L, notNullable, "password_hash must be nullable after V11");

        long sub = scalar(
                "select count(*) from information_schema.columns " +
                "where table_name='app_user' and column_name='google_sub'");
        assertEquals(1L, sub, "app_user.google_sub must exist");
    }

    @Test
    @Transactional
    void loginTicketTableExists() {
        long table = scalar(
                "select count(*) from information_schema.tables where table_name='login_ticket'");
        assertEquals(1L, table, "login_ticket table must exist");
        assertTrue(scalar("select count(*) from login_ticket") >= 0, "login_ticket must be queryable");
    }
}
