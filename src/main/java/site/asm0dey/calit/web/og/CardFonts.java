package site.asm0dey.calit.web.og;

import jakarta.enterprise.context.ApplicationScoped;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The card's typefaces, loaded once off the classpath.
 *
 * <p>All faces are STATIC instances baked with {@code fonttools varLib.instancer} (see
 * {@code src/main/resources/fonts/README.md}): AWT ignores a variable font's {@code fvar} axes and
 * silently loads the default instance, so a weight cannot be chosen at runtime.</p>
 *
 * <p>Rubik carries user-supplied text because it covers Latin, Cyrillic, Hebrew and Arabic in one
 * file — the site's own Hanken Grotesk is Latin-only. Hanken is still the wordmark and Fraunces the
 * chip, matching {@code .lp-brand} on the landing page; both strings are always the literal
 * "calit".</p>
 */
@ApplicationScoped
public class CardFonts {

    final Font rubikRegular = load("Rubik-Regular.ttf");

    final Font rubikSemiBold = load("Rubik-SemiBold.ttf");

    final Font notoRegular = load("NotoSans-Regular.ttf");

    final Font notoSemiBold = load("NotoSans-SemiBold.ttf");

    final Font notoHebrew = load("NotoSansHebrew-Regular.ttf");

    final Font hankenBold = load("HankenGrotesk-Bold.ttf");

    final Font frauncesChip = load("Fraunces-Chip.ttf");

    public Font regular() {
        return rubikRegular;
    }

    public Font semibold() {
        return rubikSemiBold;
    }

    /** Hanken Grotesk Bold — the site sets the "calit" wordmark in the body sans at weight 700. */
    public Font wordmark() {
        return hankenBold;
    }

    /** Fraunces — the site sets the chip's "c" in it. */
    public Font chip() {
        return frauncesChip;
    }

    /** Ordered fallback chain: AWT does no automatic fallback for createFont-loaded fonts. */
    public List<Font> chain(boolean semibold) {
        return semibold
                ? List.of(rubikSemiBold, notoSemiBold, notoHebrew)
                : List.of(rubikRegular, notoRegular, notoHebrew);
    }

    static Font load(String name) {
        try (var in = CardFonts.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("font resource missing: /fonts/" + name);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (FontFormatException e) {
            throw new IllegalStateException("unreadable font: " + name, e);
        }
    }
}
