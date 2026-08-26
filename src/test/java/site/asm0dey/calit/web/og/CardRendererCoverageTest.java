package site.asm0dey.calit.web.og;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * White-box coverage for {@link CardRenderer}'s fit ladder and {@code renderable()} boundaries,
 * split out from {@link CardRendererTest} because it needs {@code @QuarkusTest}: quarkus-jacoco
 * only instruments code reached through a booted Quarkus application, and {@link CardRendererTest}
 * deliberately stays a plain unit test (fast, no container) for its black-box PNG assertions. A
 * CDI-injected {@link CardRenderer} here is the same production bean {@code OgImageResource} calls,
 * just invoked directly instead of through HTTP, so these package-private calls are as real as
 * {@link CardRendererTest}'s -- they just also count towards the coverage report.
 */
@QuarkusTest
class CardRendererCoverageTest {

    @Inject
    CardRenderer renderer;

    @Test
    void headlineShrinksToFitBeforeWrapping() {
        // First fit-ladder stage: a headline too wide at 74 but narrow enough to fit at a smaller
        // size stays a single line instead of wrapping.
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var g = probe.createGraphics();

        var lines = renderer.fitHeadline(g, "Quarterly review");

        assertEquals(1, lines.size(), "still fits on one line once shrunk");
        float size = lines.getFirst().getFirst().font().getSize2D();
        assertTrue(size < 74f, "expected the ladder to shrink below the default 74, got " + size);
        assertTrue(size >= 52f, "the ladder must not shrink past its floor of 52, got " + size);
        int maxWidth = CardRenderer.SAFE_X1 - CardRenderer.SAFE_X0 - 40;
        assertTrue(TextRuns.width(g, lines.getFirst()) <= maxWidth);
        g.dispose();
    }

    @Test
    void headlineWrapsToTwoLinesWhenShrinkingIsNotEnough() {
        // Second fit-ladder stage: no size in 74..52 fits one line, but splitting at a word boundary
        // does -- both lines land inside the safe square with no ellipsis needed.
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var g = probe.createGraphics();

        var lines = renderer.fitHeadline(g, "Quarterly architecture review session");

        assertEquals(2, lines.size());
        int maxWidth = CardRenderer.SAFE_X1 - CardRenderer.SAFE_X0 - 40;
        for (var line : lines) {
            String text = line.stream().map(TextRuns.Run::text).reduce("", String::concat);
            assertFalse(text.endsWith("…"), "wrapping alone should be enough here, got: " + text);
            assertTrue(TextRuns.width(g, line) <= maxWidth, "line must fit the safe square: " + text);
        }
        g.dispose();
    }

    @Test
    void secondWrappedLineIsEllipsizedWhenStillTooLong() {
        // Third fit-ladder stage: the first wrapped line fits, but the remainder still overflows at
        // size 52 -- only the second line should carry the ellipsis, and both must stay in-bounds.
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var g = probe.createGraphics();

        var lines = renderer.fitHeadline(g, "Quarterly architecture review and roadmap planning");

        assertEquals(2, lines.size());
        int maxWidth = CardRenderer.SAFE_X1 - CardRenderer.SAFE_X0 - 40;
        String firstLine = lines.get(0).stream().map(TextRuns.Run::text).reduce("", String::concat);
        String secondLine = lines.get(1).stream().map(TextRuns.Run::text).reduce("", String::concat);
        assertFalse(firstLine.endsWith("…"), "first line should fit without ellipsis: " + firstLine);
        assertTrue(secondLine.endsWith("…"), "second line should be ellipsized: " + secondLine);
        assertTrue(TextRuns.width(g, lines.get(0)) <= maxWidth);
        assertTrue(TextRuns.width(g, lines.get(1)) <= maxWidth);
        g.dispose();
    }

    @Test
    void multiLineRenderMovesTheHeadlineAndPillLikeSingleLineDoes() throws Exception {
        // render()'s "lines.size() > 1 ? ... : ..." ternaries (headline start-y, then the meta
        // pill's y) only take their multi-line side when a card actually wraps -- every card seeded
        // through OgImageResourceTest uses a short one-line type name, so this side was unexercised.
        byte[] png = renderer.render(
                new CardRenderer.Card("Ada Lovelace", "Quarterly architecture review session", "15, 30 or 60 min"));
        var img = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(1200, img.getWidth());
        assertEquals(630, img.getHeight());

        var dark = 0;
        for (var x = CardRenderer.SAFE_X0; x < CardRenderer.SAFE_X1; x++) {
            for (var y = 0; y < 630; y++) {
                if ((img.getRGB(x, y) & 0xFF) < 0x60) {
                    dark++;
                }
            }
        }
        assertTrue(dark > 2000, "expected a two-line headline plus pill inside the safe square, found " + dark);
    }

    @Test
    void pathologicalSingleWordIsEllipsizedAndFitsTheSafeSquare() {
        // A single unbroken word cannot be wrapped, so the fit ladder's last stage (ellipsize) must
        // still catch it on the FIRST line and return early -- mirrors
        // CardRendererTest.pathologicalSingleWordIsEllipsizedAndFitsTheSafeSquare, but through the
        // CDI-injected bean so this specific branch (fitHeadline's first-line-still-overflows path)
        // counts towards coverage.
        var unbrokenWord =
                "Supercalifragilisticexpialidociousantidisestablishmentarianismfloccinaucinihilipilification";
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var g = probe.createGraphics();

        var lines = renderer.fitHeadline(g, unbrokenWord);

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

    @Test
    void ownerTextNoShippedFontCanDrawIsUnrenderable() {
        // Mirrors CardRendererTest.reportsUnrenderableText's CJK type case but for the owner slot,
        // exercising the covered(owner) operand of renderable()'s AND chain on its false side.
        assertFalse(renderer.renderable(new CardRenderer.Card("コーヒー", "Coffee chat", "30 min")));
    }

    @Test
    void metaTextNoShippedFontCanDrawIsUnrenderable() {
        // Exercises the covered(meta) operand's false side -- reportsUnrenderableText only reaches
        // the type operand before short-circuiting.
        assertFalse(renderer.renderable(new CardRenderer.Card("Ada", "Coffee chat", "コーヒー")));
    }

    @Test
    void blankOwnerAndMetaAreStillRenderable() {
        // Owner and meta are optional -- nullToEmpty("").isBlank()-driven skips in render() mean a
        // blank/null owner or meta must NOT make the card unrenderable, only a blank type does.
        assertTrue(renderer.renderable(new CardRenderer.Card(null, "Coffee chat", null)));
        assertTrue(renderer.renderable(new CardRenderer.Card("", "Coffee chat", "")));
    }
}
