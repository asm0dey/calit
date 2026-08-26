package site.asm0dey.calit.web.og;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CardRendererTest {

    static final CardRenderer RENDERER = new CardRenderer(new CardFonts());

    static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void rendersA1200x630Png() throws Exception {
        byte[] png = RENDERER.render(new CardRenderer.Card("Ada Lovelace", "Coffee chat", "30 min"));
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(png, 4));
        var img = decode(png);
        assertEquals(1200, img.getWidth());
        assertEquals(630, img.getHeight());
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
        assertTrue(dark > 2000, "expected text inside the safe square, found " + dark + " dark pixels");
    }

    @Test
    void keepsDecorationOutOfTheSafeSquare() throws Exception {
        BufferedImage img = decode(RENDERER.render(new CardRenderer.Card("Ada", "Coffee chat", "30 min")));
        // The indigo flanks live only outside the safe square.
        assertEquals(img.getRGB(20, 315), img.getRGB(1180, 315), "flanks should be symmetric");
        assertTrue((img.getRGB(300, 20) & 0xFFFFFF) > 0xE0E0E0, "safe square top should be background");
    }

    @Test
    void longNamesStillFit() throws Exception {
        byte[] png = RENDERER.render(new CardRenderer.Card(
                "Ada Lovelace", "Quarterly architecture review and roadmap planning session", "15, 30 or 60 min"));
        assertEquals(1200, decode(png).getWidth());
    }

    @Test
    void reportsUnrenderableText() {
        assertTrue(RENDERER.renderable(new CardRenderer.Card("Ada", "Coffee chat", "30 min")));
        assertTrue(RENDERER.renderable(new CardRenderer.Card("דנה כהן", "פגישת היכרות", "30 דק׳")));
        assertFalse(RENDERER.renderable(new CardRenderer.Card("Ada", "コーヒーチャット", "30 min")));
    }

    @Test
    void reportsABlankHeadlineAsUnrenderable() {
        // TextRuns.split("", ...) returns an empty run list, so fitHeadline/render has nothing to
        // index into (line.getFirst() throws NoSuchElementException) -- renderable() must reject a
        // blank type name so the resource falls back to the product card instead of crashing.
        assertFalse(RENDERER.renderable(new CardRenderer.Card("Ada", "", "30 min")));
        assertFalse(RENDERER.renderable(new CardRenderer.Card("Ada", null, "30 min")));
        assertFalse(RENDERER.renderable(new CardRenderer.Card("Ada", "   ", "30 min")));
    }

    @Test
    void productCardNeedsNoInput() throws Exception {
        assertEquals(1200, decode(RENDERER.product()).getWidth());
    }

    @Test
    void pathologicalSingleWordIsEllipsizedAndFitsTheSafeSquare() {
        // A single unbroken word cannot be wrapped, so the fit ladder's last stage (ellipsize) must
        // still catch it and keep it inside the safe square — not overflow past x=915.
        var unbrokenWord =
                "Supercalifragilisticexpialidociousantidisestablishmentarianismfloccinaucinihilipilification";
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var g = probe.createGraphics();

        var lines = RENDERER.fitHeadline(g, unbrokenWord);

        assertEquals(1, lines.size(), "an unbreakable single word should stay one line, not overflow");
        String rendered = lines.getFirst().stream().map(TextRuns.Run::text).reduce("", String::concat);
        assertTrue(rendered.endsWith("…"), "expected an ellipsis, got: " + rendered);
        assertTrue(rendered.length() < unbrokenWord.length(), "expected the word to be truncated");

        int maxWidth = CardRenderer.SAFE_X1 - CardRenderer.SAFE_X0 - 40;
        assertTrue(
                TextRuns.width(g, lines.getFirst()) <= maxWidth,
                "ellipsized line must fit the safe square, width was " + TextRuns.width(g, lines.getFirst()));
        g.dispose();
    }
}
