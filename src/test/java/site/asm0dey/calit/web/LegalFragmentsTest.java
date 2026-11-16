package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegalFragmentsTest {
    @Test
    void unsetPathServesTheShippedCopy() {
        var f = new LegalFragments(Optional.empty(), Optional.of(" "));
        assertThat(f.privacy()).isNull();
        assertThat(f.terms()).isNull();
    }

    @Test
    void missingFileServesTheShippedCopy() {
        var f = new LegalFragments(Optional.of("/nonexistent/path/does-not-exist.html"), Optional.empty());
        assertThat(f.privacy()).isNull();
    }

    /**
     * An embedded NUL makes {@code Path.of} itself throw {@link InvalidPathException}, before any I/O.
     */
    @Test
    void malformedPathServesTheShippedCopy() {
        var f = new LegalFragments(Optional.empty(), Optional.of("bad\0path.html"));
        assertThat(f.terms()).isNull();
    }

    @Test
    void readableFileReplacesTheBody(@TempDir Path dir) throws IOException {
        var file = Files.writeString(dir.resolve("policy.html"), "<h1>OPERATOR SUPPLIED POLICY</h1>");
        var f = new LegalFragments(Optional.of(file.toString()), Optional.empty());
        assertThat(f.privacy().getValue()).isEqualTo("<h1>OPERATOR SUPPLIED POLICY</h1>");
    }

    /**
     * An operator editing the file sees the change without a restart.
     */
    @Test
    void rereadsTheFileOnEveryCall(@TempDir Path dir) throws IOException {
        var file = Files.writeString(dir.resolve("terms.html"), "v1");
        var f = new LegalFragments(Optional.empty(), Optional.of(file.toString()));
        assertThat(f.terms().getValue()).isEqualTo("v1");
        Files.writeString(file, "v2");
        assertThat(f.terms().getValue()).isEqualTo("v2");
    }
}
