package site.asm0dey.calit.web.og;

import module java.base;
import module java.desktop;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CardRendererTest {
    static final CardRenderer RENDERER = new CardRenderer(new CardFonts());

    static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void rendersA1200x630Png() throws Exception {
        byte[] png = RENDERER.render(new CardRenderer.Card("Ada Lovelace", "Coffee chat", "30 min"));
        assertThat(java.util.Arrays.copyOf(png, 4)).containsExactly(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        var img = decode(png);
        assertThat(img.getWidth()).isEqualTo(1200);
        assertThat(img.getHeight()).isEqualTo(630);
    }

    @Test
    void drawsInkInsideTheSafeSquare() throws Exception {
        // Everything essential must survive a square centre crop: x in [285, 915].
        BufferedImage img = decode(RENDERER.render(new CardRenderer.Card("Ada", "Coffee chat", "30 min")));
        var dark = 0;
        for (var x = 285; x < 915; x++) {
            for (var y = 0; y < 630; y++) {
                if ((img.getRGB(x, y) & 0xFF) < 0x60) {
                    dark++;
                }
            }
        }
        assertThat(dark > 2000).as("expected text inside the safe square, found " + dark + " dark pixels").isTrue();
    }

    @Test
    void keepsDecorationOutOfTheSafeSquare() throws Exception {
        BufferedImage img = decode(RENDERER.render(new CardRenderer.Card("Ada", "Coffee chat", "30 min")));
        // The indigo flanks live only outside the safe square.
        assertThat(img.getRGB(1180, 315)).as("flanks should be symmetric").isEqualTo(img.getRGB(20, 315));
        assertThat((img.getRGB(300, 20) & 0xFFFFFF) > 0xE0E0E0).as("safe square top should be background").isTrue();
    }

    @Test
    void longNamesStillFit() throws Exception {
        byte[] png = RENDERER.render(
                new CardRenderer.Card(
                        "Ada Lovelace",
                        "Quarterly architecture review and roadmap planning session",
                        "15, 30 or 60 min"
                )
        );
        assertThat(decode(png).getWidth()).isEqualTo(1200);
    }

    @Test
    void reportsUnrenderableText() {
        assertThat(RENDERER.renderable(new CardRenderer.Card("Ada", "Coffee chat", "30 min"))).isTrue();
        assertThat(RENDERER.renderable(new CardRenderer.Card("דנה כהן", "פגישת היכרות", "30 דק׳"))).isTrue();
        assertThat(RENDERER.renderable(new CardRenderer.Card("Ada", "コーヒーチャット", "30 min"))).isFalse();
    }

    @Test
    void reportsABlankHeadlineAsUnrenderable() {
        // TextRuns.split("", ...) returns an empty run list, so fitHeadline/render has nothing to
        // index into (line.getFirst() throws NoSuchElementException) -- renderable() must reject a
        // blank type name so the resource falls back to the product card instead of crashing.
        assertThat(RENDERER.renderable(new CardRenderer.Card("Ada", "", "30 min"))).isFalse();
        assertThat(RENDERER.renderable(new CardRenderer.Card("Ada", null, "30 min"))).isFalse();
        assertThat(RENDERER.renderable(new CardRenderer.Card("Ada", "   ", "30 min"))).isFalse();
    }

    @Test
    void productCardNeedsNoInput() throws Exception {
        assertThat(decode(RENDERER.product()).getWidth()).isEqualTo(1200);
    }

    @Test
    void pathologicalSingleWordIsEllipsizedAndFitsTheSafeSquare() {
        // A single unbroken word cannot be wrapped, so the fit ladder's last stage (ellipsize) must
        // still catch it and keep it inside the safe square — not overflow past x=915.
        var unbrokenWord = "Supercalifragilisticexpialidociousantidisestablishmentarianismfloccinaucinihilipilification";
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var g = probe.createGraphics();

        var lines = RENDERER.fitHeadline(g, unbrokenWord);

        assertThat(lines).as("an unbreakable single word should stay one line, not overflow").hasSize(1);
        String rendered = lines.getFirst().stream().map(TextRuns.Run::text).reduce("", String::concat);
        assertThat(rendered).as("expected an ellipsis, got: " + rendered).endsWith("…");
        assertThat(rendered.length() < unbrokenWord.length()).as("expected the word to be truncated").isTrue();

        int maxWidth = CardRenderer.SAFE_X1 - CardRenderer.SAFE_X0 - 40;
        assertThat(TextRuns.width(g, lines.getFirst()) <= maxWidth)
            .as("ellipsized line must fit the safe square, width was " + TextRuns.width(g, lines.getFirst()))
            .isTrue();
        g.dispose();
    }
}
