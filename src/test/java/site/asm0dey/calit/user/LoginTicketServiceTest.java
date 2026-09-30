package site.asm0dey.calit.user;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

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
        var now = Instant.parse("2026-06-12T12:00:00Z");
        var uid = newUserId();

        String raw = tickets.issue(uid, now);
        assertThat(raw).as("issue returns the raw token").isNotNull();

        AppUser first = tickets.consume(raw, now.plusSeconds(5));
        assertThat(first).as("first consume returns the user").isNotNull();
        assertThat(first.id).isEqualTo(uid);

        assertThat(tickets.consume(raw, now.plusSeconds(6))).as("ticket is single-use").isNull();
    }

    @Test
    @TestTransaction
    void expiredTicketIsRejected() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        var uid = newUserId();
        String raw = tickets.issue(uid, now);

        Instant tooLate = now.plus(LoginTicketService.TTL).plus(Duration.ofSeconds(1));
        assertThat(tickets.consume(raw, tooLate)).as("expired ticket is rejected").isNull();
    }

    @Test
    @TestTransaction
    void unknownOrNullTokenRejected() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        assertThat(tickets.consume("not-a-real-token", now)).isNull();
        assertThat(tickets.consume(null, now)).isNull();
    }

    @Test
    @TestTransaction
    void eachIssueProducesADistinctToken() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        var uid = newUserId();
        assertThat(tickets.issue(uid, now)).as("tokens are random").isNotEqualTo(tickets.issue(uid, now));
    }

    @Test
    @TestTransaction
    void consumeReturnsNullWhenUserWasDeleted() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        var uid = newUserId();
        String raw = tickets.issue(uid, now);
        // User deleted between ticket issuance and consumption -> consume yields null.
        AppUser.deleteById(uid);

        assertThat(tickets.consume(raw, now.plusSeconds(5))).as("no user -> no login").isNull();
    }
}
