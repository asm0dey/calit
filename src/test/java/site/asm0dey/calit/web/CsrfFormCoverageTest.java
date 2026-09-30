package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CsrfFormCoverageTest {
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    // j_security_check forms are handled by form-auth, not the REST CSRF filter (SEC-SECRET-04).
    private static final Set<String> EXCLUDED = Set.of("login.html", "bridge.html");

    @Test
    void everyPostFormCarriesACsrfToken() throws Exception {
        try (Stream<Path> paths = Files.walk(TEMPLATES)) {
            List<Path> htmls = paths.filter(p -> p.toString().endsWith(".html")).toList();
            for (Path p : htmls) {
                var body = Files.readString(p);
                var postForms = count(body, "method=\"post\"");
                if (postForms == 0) {
                    continue;
                }
                var tokens = count(body, "{inject:csrf.token}");
                if (EXCLUDED.contains(p.getFileName().toString())) {
                    assertThat(tokens).as(p + " is a j_security_check form and must NOT carry a REST-CSRF token").isZero();
                } else {
                    assertThat(tokens)
                        .as(
                                p
                                + " has "
                                + postForms
                                + " post form(s) but only "
                                + tokens
                                + " csrf token(s) — every form-urlencoded POST must carry {inject:csrf.token}"
                        )
                        .isGreaterThanOrEqualTo(postForms);
                }
            }
        }
    }

    private static int count(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }
}
