package site.asm0dey.calit.google;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RequestTokenClassificationTest {
    @Test
    void invalidGrantExceptionIsAnIllegalStateException() {
        // Subclassing IllegalStateException keeps every existing broad catch(RuntimeException)
        // in validAccessToken/freeBusy behaving unchanged; only the probe inspects the subtype.
        GoogleInvalidGrantException e = new GoogleInvalidGrantException("dead", null);
        assertThat(e).isInstanceOf(IllegalStateException.class);
    }
}
