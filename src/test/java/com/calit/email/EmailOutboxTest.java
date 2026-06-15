package com.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmailOutboxTest {

    @Test
    void enqueuePersistsADueUnsentRow() {
        Long id = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.enqueue("a@b.com", "Subj", "<p>hi</p>", new byte[]{1, 2}, "boom"));

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
                EmailOutbox.enqueue("a@b.com", "S", "h", null, null));

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
                EmailOutbox.enqueue("a@b.com", "S", "h", null, null));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            r.attempts = 9; // next failure is the 10th -> dead
            r.deadOrBackoff("still down");
            assertEquals(10, r.attempts);
            assertNull(r.nextAttemptAt, "capped row is dead: excluded from the claim query");
        });
    }
}
