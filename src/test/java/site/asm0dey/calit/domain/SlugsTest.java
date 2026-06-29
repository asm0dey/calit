package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SlugsTest {

    @Test
    void slugifyLowercasesAndHyphenates() {
        assertEquals("intro-call", Slugs.slugify("Intro Call"));
        assertEquals("intro-call", Slugs.slugify("  Intro   Call!! "));
        assertEquals("30-min-sync", Slugs.slugify("30 Min Sync"));
    }

    @Test
    void slugifyStripsAccents() {
        assertEquals("cafe-meeting", Slugs.slugify("Café Meeting"));
    }

    @Test
    void slugifyHandlesNullAndEmpty() {
        assertEquals("", Slugs.slugify(null));
        assertEquals("", Slugs.slugify("   "));
    }
}
