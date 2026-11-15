package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BuildInfoTest {
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
