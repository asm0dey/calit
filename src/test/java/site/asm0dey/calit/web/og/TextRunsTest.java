package site.asm0dey.calit.web.og;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextRunsTest {

    static final CardFonts FONTS = new CardFonts();

    @Test
    void latinIsOneRunInThePrimaryFont() {
        List<TextRuns.Run> runs = TextRuns.split("Coffee chat", FONTS.chain(false), 40f);
        assertEquals(1, runs.size());
        assertEquals("Coffee chat", runs.getFirst().text());
        assertEquals("Rubik Regular", runs.getFirst().font().getFontName());
    }

    @Test
    void cyrillicAndHebrewStayInThePrimaryFont() {
        assertEquals(1, TextRuns.split("Знакомство", FONTS.chain(false), 40f).size());
        assertEquals(1, TextRuns.split("פגישה", FONTS.chain(false), 40f).size());
    }

    @Test
    void greekFallsBackToNoto() {
        List<TextRuns.Run> runs = TextRuns.split("Συνάντηση", FONTS.chain(false), 40f);
        assertEquals(1, runs.size());
        assertEquals("Noto Sans Regular", runs.getFirst().font().getFontName());
    }

    @Test
    void mixedScriptsSplitIntoSeparateRuns() {
        List<TextRuns.Run> runs = TextRuns.split("Coffee Ω", FONTS.chain(false), 40f);
        assertTrue(runs.size() >= 2, "expected a Latin run and a Greek run, got " + runs.size());
    }

    @Test
    void coverageReportsWhatNoShippedFontCanDraw() {
        assertTrue(TextRuns.covered("Coffee chat", FONTS.chain(false)));
        assertTrue(TextRuns.covered("פגישת היכרות", FONTS.chain(false)));
        assertFalse(TextRuns.covered("コーヒーチャット", FONTS.chain(false)));
        assertFalse(TextRuns.covered("Coffee ☕ chat", FONTS.chain(false)));
    }

    @Test
    void semiboldChainUsesSemiboldFallback() {
        Font f = TextRuns.split("Συνάντηση", FONTS.chain(true), 40f).getFirst().font();
        // "Noto Sans Regular" and "Noto Sans SemiBold" are both valid "Noto" matches, so the
        // assertion has to name the SemiBold cut specifically: wiring notoRegular into
        // chain(true) by mistake would still pass a bare contains("Noto") check.
        assertEquals("Noto Sans SemiBold", f.getFontName());
    }
}
