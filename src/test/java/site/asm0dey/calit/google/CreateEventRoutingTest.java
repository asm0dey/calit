package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CreateEventRoutingTest {

    @Inject
    GoogleCalendarPort port;

    @Test
    @Transactional
    void noWriteTargetThrows() {
        assertThrows(
                IllegalStateException.class,
                () -> port.createEvent(
                        1L,
                        "s",
                        "d",
                        Instant.now(),
                        Instant.now().plusSeconds(1800),
                        List.of("a@example.com"),
                        true,
                        null));
    }
}
