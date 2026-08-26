package site.asm0dey.calit.web.og;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Draws the 1200x630 link-preview card.
 *
 * <p>Composition is CENTRED on purpose. Preview clients crop differently and a square centre crop is
 * the harsh case, so everything essential lives inside the central 630x630 square (x 285..915) and
 * the indigo flanks outside it are pure decoration. Centring also means RTL needs no mirroring —
 * there is no left edge to flip.</p>
 *
 * <p>Renders in about 2 ms, which is why nothing here is cached: see the endpoint's ETag and
 * Cache-Control instead.</p>
 */
@ApplicationScoped
public class CardRenderer {

    static final int W = 1200;

    static final int H = 630;

    static final int SAFE_X0 = (W - H) / 2;

    static final int SAFE_X1 = SAFE_X0 + H;

    static final int FLANK = 150;

    static final Color BG = new Color(0xFFFFFF);

    static final Color INK = new Color(0x16140F);

    static final Color INK_2 = new Color(0x565049);

    static final Color INDIGO = new Color(0x4F46E5);

    static final Color INDIGO_2 = new Color(0x6D65F0);

    static final Color MIST = new Color(0xF1F0FE);

    static final String PRODUCT_OWNER = "";

    static final String PRODUCT_TYPE = "Book a meeting";

    static final String PRODUCT_META = "Pick a meeting type and book a time.";

    final CardFonts fonts;

    @Inject
    public CardRenderer(CardFonts fonts) {
        this.fonts = fonts;
    }

    /** Owner name, meeting-type name, and the meta line ("30 min · Google Meet"). */
    public record Card(String owner, String type, String meta) {}

    /**
     * False when some shipped font cannot draw the text, or the headline would be empty — the
     * caller then serves the product card.
     *
     * <p>{@code fitHeadline} has no content to lay out for a blank {@code type}: {@link
     * TextRuns#split} of an empty string returns an empty run list, and {@code render()} always
     * indexes into the first run of the first line to read its font size. Rejecting a blank
     * headline here — the same "can't render this, fall back" path every other unrenderable input
     * already takes — is less special-casing than teaching {@code render()} to skip the headline
     * block for one specific input shape.
     *
     * <p>Checked against {@code fonts.chain(false)} even though the headline itself draws with
     * {@code chain(true)}: both chains pair the same three families (Rubik/Noto Sans/Noto Sans
     * Hebrew) at different weights, and a weight variant of one family covers the same code points
     * as its sibling, so coverage is identical either way.
     */
    public boolean renderable(Card card) {
        List<Font> chain = fonts.chain(false);
        return !nullToEmpty(card.type()).isBlank()
                && TextRuns.covered(nullToEmpty(card.owner()), chain)
                && TextRuns.covered(nullToEmpty(card.type()), chain)
                && TextRuns.covered(nullToEmpty(card.meta()), chain);
    }

    public byte[] product() {
        return render(new Card(PRODUCT_OWNER, PRODUCT_TYPE, PRODUCT_META));
    }

    public byte[] render(Card card) {
        var img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        g.setPaint(new GradientPaint(0, 0, INDIGO, 0, H, INDIGO_2));
        g.fillRect(0, 0, FLANK, H);
        g.fillRect(W - FLANK, 0, FLANK, H);

        var cx = W / 2;
        drawLockup(g, cx);

        if (!nullToEmpty(card.owner()).isBlank()) {
            g.setColor(INK_2);
            TextRuns.drawCentered(g, TextRuns.split(card.owner(), fonts.chain(false), 32f), cx, 268);
        }

        List<List<TextRuns.Run>> lines = fitHeadline(g, nullToEmpty(card.type()));
        g.setColor(INK);
        var y = lines.size() > 1 ? 348 : 372;
        for (List<TextRuns.Run> line : lines) {
            TextRuns.drawCentered(g, line, cx, y);
            y += (int) (line.getFirst().font().getSize() * 1.1);
        }

        if (!nullToEmpty(card.meta()).isBlank()) {
            drawPill(g, cx, lines.size() > 1 ? y + 16 : 424, card.meta());
        }

        g.dispose();
        return toPng(img);
    }

    /** Chip + wordmark, matching .lp-brand: Fraunces inside the chip, the sans for "calit". */
    void drawLockup(Graphics2D g, int cx) {
        var tile = 56;
        Font wordFont = fonts.wordmark().deriveFont(34f);
        var wordWidth = trackedWidth(g, "calit", wordFont);
        // lockWidth is genuinely integral: KEY_FRACTIONALMETRICS is never set above, so it defaults to
        // OFF and AWT rounds every glyph advance to whole pixels when drawing — meaning wordWidth's int
        // (from FontMetrics.stringWidth) already equals the true drawn width. Only the halving below
        // needs float: integer division there truncated the centring offset by up to half a pixel — a
        // real defect, not just an S2184 nit, because the whole card's crop-safety design leans on this
        // lockup being exactly centred (see the class javadoc).
        var lockWidth = tile + 14 + wordWidth;
        var x = cx - lockWidth / 2f;

        g.setColor(INDIGO);
        // RoundRectangle2D's arcw/arch are the full arc DIAMETER, but the spec's "30% corner radius"
        // is a RADIUS (matching CSS border-radius) — so the diameter passed here is double that, 60%.
        g.fill(new RoundRectangle2D.Float(x, 74, tile, tile, tile * 0.6f, tile * 0.6f));
        g.setColor(Color.WHITE);
        g.setFont(fonts.chip().deriveFont(tile * 0.6f));
        var chipMetrics = g.getFontMetrics();
        g.drawString(
                "c",
                x + (tile - chipMetrics.stringWidth("c")) / 2f,
                74 + tile / 2f + (chipMetrics.getAscent() - chipMetrics.getDescent()) / 2f);

        g.setColor(INK);
        drawTracked(g, "calit", wordFont, x + tile + 14, 74 + tile / 2f + 12);
    }

    /**
     * The site tracks the wordmark at -0.02em. {@code TextAttribute.TRACKING} applies that natively
     * in a single {@code drawString}, so kerning pairs are honoured — a per-character loop stepping by
     * {@code fm.charWidth} both rounds each advance to an integer and ignores kerning entirely.
     */
    void drawTracked(Graphics2D g, String text, Font font, float x, float baseline) {
        g.setFont(trackedFont(font));
        g.drawString(text, x, baseline);
    }

    int trackedWidth(Graphics2D g, String text, Font font) {
        var fm = g.getFontMetrics(trackedFont(font));
        return fm.stringWidth(text);
    }

    /** Fixed-order attribute map — required so rendering stays byte-for-byte deterministic. */
    static Font trackedFont(Font font) {
        return font.deriveFont(Map.of(TextAttribute.TRACKING, -0.02f));
    }

    /** Shrink to fit the safe square, then wrap to two lines, then ellipsize. */
    List<List<TextRuns.Run>> fitHeadline(Graphics2D g, String text) {
        var maxWidth = SAFE_X1 - SAFE_X0 - 40;
        for (float size = 74; size >= 52; size -= 3) {
            List<TextRuns.Run> runs = TextRuns.split(text, fonts.chain(true), size);
            if (TextRuns.width(g, runs) <= maxWidth) {
                return List.of(runs);
            }
        }
        var words = text.split(" ");
        var first = new StringBuilder();
        var i = 0;
        for (; i < words.length; i++) {
            var probe = first.isEmpty() ? words[i] : first + " " + words[i];
            if (!first.isEmpty() && TextRuns.width(g, TextRuns.split(probe, fonts.chain(true), 52f)) > maxWidth) {
                break;
            }
            first.setLength(0);
            first.append(probe);
        }
        List<List<TextRuns.Run>> out = new ArrayList<>();
        var firstText = first.toString();
        // A single word (or the leading word alone) can already overflow maxWidth — wrapping can't
        // help an unbreakable token, so fall through to the ellipsize stage for the first line too.
        if (TextRuns.width(g, TextRuns.split(firstText, fonts.chain(true), 52f)) > maxWidth) {
            out.add(TextRuns.split(ellipsize(g, firstText, maxWidth), fonts.chain(true), 52f));
            return out;
        }
        out.add(TextRuns.split(firstText, fonts.chain(true), 52f));
        var rest = String.join(" ", Arrays.copyOfRange(words, i, words.length));
        if (!rest.isBlank()) {
            out.add(TextRuns.split(ellipsize(g, rest, maxWidth), fonts.chain(true), 52f));
        }
        return out;
    }

    String ellipsize(Graphics2D g, String text, int maxWidth) {
        if (TextRuns.width(g, TextRuns.split(text, fonts.chain(true), 52f)) <= maxWidth) {
            return text;
        }
        var sb = new StringBuilder(text);
        while (sb.length() > 1 && TextRuns.width(g, TextRuns.split(sb + "…", fonts.chain(true), 52f)) > maxWidth) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb + "…";
    }

    void drawPill(Graphics2D g, int cx, int y, String text) {
        List<TextRuns.Run> runs = TextRuns.split(text, fonts.chain(false), 28f);
        int textWidth = TextRuns.width(g, runs);
        FontMetrics fm = g.getFontMetrics(fonts.regular().deriveFont(28f));
        var padX = 24;
        var height = fm.getHeight() + 14;
        var width = textWidth + padX * 2;
        g.setColor(MIST);
        g.fill(new RoundRectangle2D.Float(cx - width / 2f, y, width, height, height, height));
        g.setColor(INDIGO);
        TextRuns.drawCentered(g, runs, cx, (int) (y + height / 2f + (fm.getAscent() - fm.getDescent()) / 2f));
    }

    static byte[] toPng(BufferedImage img) {
        try (var out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(img, "png", out)) {
                throw new IllegalStateException("no PNG writer available");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
