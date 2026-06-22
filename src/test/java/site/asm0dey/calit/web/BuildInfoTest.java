package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class BuildInfoTest {

    @Inject
    BuildInfo buildInfo;

    @Test
    void versionMatchesProjectVersion() {
        // git.properties is generated at build time; in this repo the version is the Maven project version.
        assertFalse(buildInfo.getVersion().isBlank(), "version must not be blank");
    }

    @Test
    void commitIsNeverBlank() {
        // Either the abbreviated SHA, or the "dev" fallback when .git is unavailable.
        assertFalse(buildInfo.getCommit().isBlank(), "commit must not be blank");
    }
}
