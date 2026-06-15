package com.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmailOutboxTest {

    @Test
    void enqueuePersistsADueUnsentRow() {
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "Subj", "<p>hi</p>", new byte[]{1, 2}, null, "boom"));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            assertNotNull(r);
            assertEquals("a@b.com", r.recipient);
            assertEquals(0, r.attempts);
            assertNull(r.sentAt);
            assertNotNull(r.nextAttemptAt, "enqueued rows are due immediately");
            assertEquals("boom", r.lastError);
        });
    }

    @Test
    void backoffBumpsAttemptsAndPushesNextAttempt() {
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "S", "h", null, null, null));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            java.time.Instant before = r.nextAttemptAt;
            r.deadOrBackoff("smtp down");
            assertEquals(1, r.attempts);
            assertEquals("smtp down", r.lastError);
            assertTrue(r.nextAttemptAt.isAfter(before), "next attempt pushed into the future");
        });
    }

    @Test
    void attemptCapMarksRowDead() {
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "S", "h", null, null, null));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            r.attempts = 9; // next failure is the 10th -> dead
            r.deadOrBackoff("still down");
            assertEquals(10, r.attempts);
            assertNull(r.nextAttemptAt, "capped row is dead: excluded from the claim query");
        });
    }

    @Test
    void pastDeadlineAndMarkExpired() {
        java.time.Instant now = java.time.Instant.parse("2026-06-15T12:00:00Z");
        EmailOutbox r = new EmailOutbox();
        // No deadline -> never past it.
        r.notAfter = null;
        assertFalse(r.pastDeadline(now));
        // Deadline in the future -> not yet.
        r.notAfter = now.plusSeconds(60);
        assertFalse(r.pastDeadline(now));
        // Deadline in the past -> past it; markExpired kills the row without sending.
        r.notAfter = now.minusSeconds(1);
        assertTrue(r.pastDeadline(now));
        r.markExpired();
        assertNull(r.nextAttemptAt, "expired row is dead");
    }
}
