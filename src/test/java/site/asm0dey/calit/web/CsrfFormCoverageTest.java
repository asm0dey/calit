package site.asm0dey.calit.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CsrfFormCoverageTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    // j_security_check forms are handled by form-auth, not the REST CSRF filter (SEC-SECRET-04).
    private static final Set<String> EXCLUDED = Set.of("login.html", "bridge.html");

    @Test
    void everyPostFormCarriesACsrfToken() throws IOException {
        try (Stream<Path> paths = Files.walk(TEMPLATES)) {
            List<Path> htmls = paths.filter(p -> p.toString().endsWith(".html")).toList();
            for (Path p : htmls) {
                String body = Files.readString(p);
                int postForms = count(body, "method=\"post\"");
                if (postForms == 0) continue;
                int tokens = count(body, "{inject:csrf.token}");
                if (EXCLUDED.contains(p.getFileName().toString())) {
                    assertEquals(0, tokens, p + " is a j_security_check form and must NOT carry a REST-CSRF token");
                } else {
                    assertTrue(tokens >= postForms,
                            p + " has " + postForms + " post form(s) but only " + tokens
                              + " csrf token(s) — every form-urlencoded POST must carry {inject:csrf.token}");
                }
            }
        }
    }

    private static int count(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }
}
