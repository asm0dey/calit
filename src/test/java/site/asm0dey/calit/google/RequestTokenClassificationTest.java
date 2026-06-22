package site.asm0dey.calit.google;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestTokenClassificationTest {

    @Test
    void invalidGrantExceptionIsAnIllegalStateException() {
        // Subclassing IllegalStateException keeps every existing broad catch(RuntimeException)
        // in validAccessToken/freeBusy behaving unchanged; only the probe inspects the subtype.
        GoogleInvalidGrantException e = new GoogleInvalidGrantException("dead", null);
        assertTrue(e instanceof IllegalStateException);
    }
}
