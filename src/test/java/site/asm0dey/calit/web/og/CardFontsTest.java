package site.asm0dey.calit.web.og;

import module java.desktop;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
// java.desktop's module import would otherwise bind List to java.awt.List
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code @QuarkusTest} (not a plain unit test like {@link TextRunsTest}) because quarkus-jacoco
 * only instruments code paths reached through a booted Quarkus application -- a plain {@code new
 * CardFonts()} in an un-annotated test class runs against un-instrumented bytecode and is
 * invisible to the coverage report, no matter how real the assertion is.
 */
@QuarkusTest
class CardFontsTest {
    @Inject
    CardFonts fonts;

    @Test
    void regularIsRubikRegular() {
        assertThat(fonts.regular().getFontName()).isEqualTo("Rubik Regular");
    }

    @Test
    void semiboldIsRubikSemiBold() {
        // regular() and semibold() must be genuinely different weights, not the same face loaded
        // twice -- the whole point of the pre-baked static instances (see the class javadoc).
        assertThat(fonts.semibold().getFontName()).isEqualTo("Rubik SemiBold");
    }

    @Test
    void wordmarkIsHankenGroteskBold() {
        assertThat(fonts.wordmark().getFontName()).isEqualTo("Hanken Grotesk Bold");
    }

    @Test
    void chipIsFraunces() {
        assertThat(fonts.chip().getFontName())
            .as("chip font should be a Fraunces instance, got " + fonts.chip().getFontName())
            .startsWith("Fraunces");
    }

    @Test
    void regularChainPairsRegularWeightsInFallbackOrder() {
        List<Font> chain = fonts.chain(false);
        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).getFontName()).isEqualTo("Rubik Regular");
        assertThat(chain.get(1).getFontName()).isEqualTo("Noto Sans Regular");
        assertThat(chain.get(2).getFontName()).isEqualTo("Noto Sans Hebrew Regular");
    }

    @Test
    void semiboldChainPairsSemiboldWeightsInFallbackOrder() {
        // Noto Sans Hebrew ships only a Regular instance -- the semibold chain must still fall back
        // to it rather than omitting Hebrew coverage entirely.
        List<Font> chain = fonts.chain(true);
        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).getFontName()).isEqualTo("Rubik SemiBold");
        assertThat(chain.get(1).getFontName()).isEqualTo("Noto Sans SemiBold");
        assertThat(chain.get(2).getFontName()).isEqualTo("Noto Sans Hebrew Regular");
    }
}
