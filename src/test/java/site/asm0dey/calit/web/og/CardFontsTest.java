package site.asm0dey.calit.web.og;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.awt.Font;
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
        assertEquals("Rubik Regular", fonts.regular().getFontName());
    }

    @Test
    void semiboldIsRubikSemiBold() {
        // regular() and semibold() must be genuinely different weights, not the same face loaded
        // twice -- the whole point of the pre-baked static instances (see the class javadoc).
        assertEquals("Rubik SemiBold", fonts.semibold().getFontName());
    }

    @Test
    void wordmarkIsHankenGroteskBold() {
        assertEquals("Hanken Grotesk Bold", fonts.wordmark().getFontName());
    }

    @Test
    void chipIsFraunces() {
        assertTrue(
                fonts.chip().getFontName().startsWith("Fraunces"),
                "chip font should be a Fraunces instance, got " + fonts.chip().getFontName());
    }

    @Test
    void regularChainPairsRegularWeightsInFallbackOrder() {
        List<Font> chain = fonts.chain(false);
        assertEquals(3, chain.size());
        assertEquals("Rubik Regular", chain.get(0).getFontName());
        assertEquals("Noto Sans Regular", chain.get(1).getFontName());
        assertEquals("Noto Sans Hebrew Regular", chain.get(2).getFontName());
    }

    @Test
    void semiboldChainPairsSemiboldWeightsInFallbackOrder() {
        // Noto Sans Hebrew ships only a Regular instance -- the semibold chain must still fall back
        // to it rather than omitting Hebrew coverage entirely.
        List<Font> chain = fonts.chain(true);
        assertEquals(3, chain.size());
        assertEquals("Rubik SemiBold", chain.get(0).getFontName());
        assertEquals("Noto Sans SemiBold", chain.get(1).getFontName());
        assertEquals("Noto Sans Hebrew Regular", chain.get(2).getFontName());
    }
}
