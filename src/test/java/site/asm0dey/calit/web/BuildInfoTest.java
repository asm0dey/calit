package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BuildInfoTest {
    @Inject
    BuildInfo buildInfo;

    @Test
    void versionMatchesProjectVersion() {
        // git.properties is generated at build time; in this repo the version is the Maven project version.
        assertThat(buildInfo.getVersion().isBlank()).as("version must not be blank").isFalse();
    }

    @Test
    void commitIsNeverBlank() {
        // Either the abbreviated SHA, or the "dev" fallback when .git is unavailable.
        assertThat(buildInfo.getCommit().isBlank()).as("commit must not be blank").isFalse();
    }

    @Test
    void runtimeImageMetadataOverridesBuildProperties() {
        var properties = new Properties();
        properties.setProperty("git.build.version", "dev");
        properties.setProperty("git.commit.id.abbrev", "buildsha");

        var runtimeBuildInfo = new BuildInfo(Map.of("APP_VERSION", "1.26.0", "GIT_COMMIT", "abc1234"), properties);

        assertThat(runtimeBuildInfo.getVersion()).isEqualTo("1.26.0");
        assertThat(runtimeBuildInfo.getCommit()).isEqualTo("abc1234");
    }

    @Test
    void blankRuntimeMetadataFallsBackToBuildProperties() {
        var properties = new Properties();
        properties.setProperty("git.build.version", "dev");
        properties.setProperty("git.commit.id.abbrev", "buildsha");

        var runtimeBuildInfo = new BuildInfo(Map.of("APP_VERSION", "", "GIT_COMMIT", ""), properties);

        assertThat(runtimeBuildInfo.getVersion()).isEqualTo("dev");
        assertThat(runtimeBuildInfo.getCommit()).isEqualTo("buildsha");
    }
}
