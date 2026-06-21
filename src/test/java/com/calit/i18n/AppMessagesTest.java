package com.calit.i18n;

import io.quarkus.qute.i18n.Localized;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AppMessagesTest {
    @Inject AppMessages en;
    @Inject @Localized("de") AppMessages de;

    @Test void englishDefault() { assertEquals("Cancel", en.common_cancel()); }
    @Test void germanOverride() { assertEquals("Abbrechen", de.common_cancel()); }
}
