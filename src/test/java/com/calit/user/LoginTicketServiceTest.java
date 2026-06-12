package com.calit.user;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class LoginTicketServiceTest {

    @Inject
    LoginTicketService tickets;

    private Long newUserId() {
        AppUser u = AppUser.createGoogleUser("ticket-user", "sub-" + System.nanoTime());
        u.persistAndFlush();
        return u.id;
    }

    @Test
    @TestTransaction
    void issuedTicketIsConsumedExactlyOnce() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        Long uid = newUserId();

        String raw = tickets.issue(uid, now);
        assertNotNull(raw, "issue returns the raw token");

        AppUser first = tickets.consume(raw, now.plusSeconds(5));
        assertNotNull(first, "first consume returns the user");
        assertEquals(uid, first.id);

        assertNull(tickets.consume(raw, now.plusSeconds(6)), "ticket is single-use");
    }

    @Test
    @TestTransaction
    void expiredTicketIsRejected() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        Long uid = newUserId();
        String raw = tickets.issue(uid, now);

        Instant tooLate = now.plus(LoginTicketService.TTL).plus(Duration.ofSeconds(1));
        assertNull(tickets.consume(raw, tooLate), "expired ticket is rejected");
    }

    @Test
    @TestTransaction
    void unknownOrNullTokenRejected() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        assertNull(tickets.consume("not-a-real-token", now));
        assertNull(tickets.consume(null, now));
    }

    @Test
    @TestTransaction
    void eachIssueProducesADistinctToken() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        Long uid = newUserId();
        assertNotEquals(tickets.issue(uid, now), tickets.issue(uid, now), "tokens are random");
    }

    @Test
    @TestTransaction
    void consumeReturnsNullWhenUserWasDeleted() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        Long uid = newUserId();
        String raw = tickets.issue(uid, now);

        // User deleted between ticket issuance and consumption -> consume yields null.
        AppUser.deleteById(uid);

        assertNull(tickets.consume(raw, now.plusSeconds(5)), "no user -> no login");
    }
}
