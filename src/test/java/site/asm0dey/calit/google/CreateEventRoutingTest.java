package site.asm0dey.calit.google;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CreateEventRoutingTest {
    @Inject
    GoogleCalendarPort port;

    @Test
    @Transactional
    void noWriteTargetThrows() {
        var start = Instant.now();
        var end = Instant.now().plusSeconds(1800);
        var attendeeEmails = List.of("a@example.com");
        assertThrows(IllegalStateException.class, () -> port.createEvent(
                1L,
                null,
                "s",
                "d",
                start,
                end,
                attendeeEmails,
                true,
                null
        ));
    }
}
