package site.asm0dey.calit.web.og;

import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a string into runs that each have a font able to draw them.
 *
 * <p>AWT performs no fallback for fonts loaded with {@code Font.createFont}, so a Greek meeting
 * name drawn straight through Rubik renders as boxes. Bidi *within* a run is still handled by
 * {@code drawString}, which is why Hebrew and mixed "30 דק׳ · Google Meet" need no special case.</p>
 */
public final class TextRuns {

    private TextRuns() {}

    public record Run(String text, Font font) {}

    /** Every character drawable by some font in the chain. */
    public static boolean covered(String text, List<Font> chain) {
        return text.codePoints().allMatch(cp -> chain.stream().anyMatch(f -> f.canDisplay(cp)));
    }

    public static List<Run> split(String text, List<Font> chain, float size) {
        List<Run> out = new ArrayList<>();
        var current = new StringBuilder();
        Font currentFont = null;
        for (var i = 0; i < text.length(); ) {
            var cp = text.codePointAt(i);
            var font = chain.stream().filter(f -> f.canDisplay(cp)).findFirst().orElse(chain.getFirst());
            if (currentFont == null || font == currentFont) {
                currentFont = font;
            } else {
                out.add(new Run(current.toString(), currentFont.deriveFont(size)));
                current.setLength(0);
                currentFont = font;
            }
            current.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (!current.isEmpty()) {
            out.add(new Run(current.toString(), currentFont.deriveFont(size)));
        }
        return out;
    }

    public static int width(Graphics2D g, List<Run> runs) {
        var total = 0;
        for (Run r : runs) {
            total += g.getFontMetrics(r.font()).stringWidth(r.text());
        }
        return total;
    }

    public static void drawCentered(Graphics2D g, List<Run> runs, int centerX, int baseline) {
        var x = centerX - width(g, runs) / 2f;
        for (Run r : runs) {
            g.setFont(r.font());
            g.drawString(r.text(), x, baseline);
            x += g.getFontMetrics().stringWidth(r.text());
        }
    }
}
