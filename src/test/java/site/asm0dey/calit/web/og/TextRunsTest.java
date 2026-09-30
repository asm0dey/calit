package site.asm0dey.calit.web.og;

import module java.desktop;
import static org.assertj.core.api.Assertions.assertThat;
// java.desktop's module import would otherwise bind List to java.awt.List
import java.util.List;
import org.junit.jupiter.api.Test;

class TextRunsTest {
    static final CardFonts FONTS = new CardFonts();

    @Test
    void latinIsOneRunInThePrimaryFont() {
        List<TextRuns.Run> runs = TextRuns.split("Coffee chat", FONTS.chain(false), 40f);
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().text()).isEqualTo("Coffee chat");
        assertThat(runs.getFirst().font().getFontName()).isEqualTo("Rubik Regular");
    }

    @Test
    void cyrillicAndHebrewStayInThePrimaryFont() {
        assertThat(TextRuns.split("Знакомство", FONTS.chain(false), 40f)).hasSize(1);
        assertThat(TextRuns.split("פגישה", FONTS.chain(false), 40f)).hasSize(1);
    }

    @Test
    void greekFallsBackToNoto() {
        List<TextRuns.Run> runs = TextRuns.split("Συνάντηση", FONTS.chain(false), 40f);
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().font().getFontName()).isEqualTo("Noto Sans Regular");
    }

    @Test
    void mixedScriptsSplitIntoSeparateRuns() {
        List<TextRuns.Run> runs = TextRuns.split("Coffee Ω", FONTS.chain(false), 40f);
        assertThat(runs).as("expected a Latin run and a Greek run, got " + runs.size()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void coverageReportsWhatNoShippedFontCanDraw() {
        assertThat(TextRuns.covered("Coffee chat", FONTS.chain(false))).isTrue();
        assertThat(TextRuns.covered("פגישת היכרות", FONTS.chain(false))).isTrue();
        assertThat(TextRuns.covered("コーヒーチャット", FONTS.chain(false))).isFalse();
        assertThat(TextRuns.covered("Coffee ☕ chat", FONTS.chain(false))).isFalse();
    }

    @Test
    void semiboldChainUsesSemiboldFallback() {
        Font f = TextRuns.split("Συνάντηση", FONTS.chain(true), 40f).getFirst().font();
        // "Noto Sans Regular" and "Noto Sans SemiBold" are both valid "Noto" matches, so the
        // assertion has to name the SemiBold cut specifically: wiring notoRegular into
        // chain(true) by mistake would still pass a bare contains("Noto") check.
        assertThat(f.getFontName()).isEqualTo("Noto Sans SemiBold");
    }
}
