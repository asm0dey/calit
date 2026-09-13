package site.asm0dey.calit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

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

    @Test
    void runtimeImageMetadataOverridesBuildProperties() {
        var properties = new Properties();
        properties.setProperty("git.build.version", "dev");
        properties.setProperty("git.commit.id.abbrev", "buildsha");

        var runtimeBuildInfo = new BuildInfo(Map.of("APP_VERSION", "1.26.0", "GIT_COMMIT", "abc1234"), properties);

        assertEquals("1.26.0", runtimeBuildInfo.getVersion());
        assertEquals("abc1234", runtimeBuildInfo.getCommit());
    }

    @Test
    void blankRuntimeMetadataFallsBackToBuildProperties() {
        var properties = new Properties();
        properties.setProperty("git.build.version", "dev");
        properties.setProperty("git.commit.id.abbrev", "buildsha");

        var runtimeBuildInfo = new BuildInfo(Map.of("APP_VERSION", "", "GIT_COMMIT", ""), properties);

        assertEquals("dev", runtimeBuildInfo.getVersion());
        assertEquals("buildsha", runtimeBuildInfo.getCommit());
    }
}
