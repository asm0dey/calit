package site.asm0dey.calit.web;

/**
 * Link-preview metadata for one page. Passed to {@code base.html} as {@code og}; when it is absent
 * the template emits {@code noindex,nofollow} and no tags at all, which is what keeps the
 * capability URLs ({@code /booking/{manageToken}/*}, {@code /guest/{declineToken}/*}) from
 * unfurling: they simply never pass one.
 *
 * <p>All copy is English on purpose — {@code og:locale} is always {@code en_US} because unfurl bots
 * send no {@code calit_lang} cookie and usually no {@code Accept-Language}.</p>
 */
public record OgCard(String title, String description, String imageUrl, String pageUrl) {}
