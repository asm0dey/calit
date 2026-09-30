package site.asm0dey.calit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SlugsTest {
    @Test
    void slugifyLowercasesAndHyphenates() {
        assertThat(Slugs.slugify("Intro Call")).isEqualTo("intro-call");
        assertThat(Slugs.slugify("  Intro   Call!! ")).isEqualTo("intro-call");
        assertThat(Slugs.slugify("30 Min Sync")).isEqualTo("30-min-sync");
    }

    @Test
    void slugifyStripsAccents() {
        assertThat(Slugs.slugify("Café Meeting")).isEqualTo("cafe-meeting");
    }

    @Test
    void slugifyHandlesNullAndEmpty() {
        assertThat(Slugs.slugify(null)).isEmpty();
        assertThat(Slugs.slugify("   ")).isEmpty();
    }
}
