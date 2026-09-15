# Release Image Cache Reuse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make metadata-only tagged releases reuse the expensive JVM and native compilation layers while release containers still display the exact tag version and commit in the footer.

**Architecture:** Compile Maven artifacts with a stable `dev` revision and no release-specific inputs before the Docker build steps. Pass the tag-derived version and short commit only into the final runtime stage, where `BuildInfo` reads them from environment variables before falling back to Maven-generated `git.properties` for local builds.

**Tech Stack:** Java 25, Quarkus 3, Maven CI-friendly versions, Docker BuildKit, GitHub Actions, JUnit 5.

**Spec:** `.beans/calit-0s1x--ci-image-builds-never-reuse-the-native-image-layer.md`

## Global Constraints

- Release images must show the exact `vX.Y.Z` tag as `X.Y.Z` in the footer.
- `APP_VERSION` and `GIT_COMMIT` must not affect the JVM or native compilation layer cache key.
- `pom.xml` must remain unchanged between releases.
- Main-branch images use `edge` as their displayed version.
- Local non-container builds continue falling back to Maven-generated `git.properties`.
- Do not modify the user's existing `.beans/**` changes.
- Do not create commits; repository-level instructions reserve commits for explicit user requests.

---

### Task 1: Runtime Footer Metadata

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/BuildInfo.java`
- Modify: `src/test/java/site/asm0dey/calit/web/BuildInfoTest.java`

**Interfaces:**
- Consumes: Runtime environment keys `APP_VERSION` and `GIT_COMMIT`; fallback properties `git.build.version` and `git.commit.id.abbrev`.
- Produces: `BuildInfo(Map<String, String>, Properties)` package-private constructor and existing `getVersion()` / `getCommit()` accessors.

- [ ] **Step 1: Add failing runtime-precedence tests**

Add tests that construct `BuildInfo` with explicit maps and properties:

```java
@Test
void runtimeImageMetadataOverridesBuildProperties() {
    var properties = new Properties();
    properties.setProperty("git.build.version", "dev");
    properties.setProperty("git.commit.id.abbrev", "buildsha");

    var runtimeBuildInfo =
            new BuildInfo(Map.of("APP_VERSION", "1.26.0", "GIT_COMMIT", "abc1234"), properties);

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
```

- [ ] **Step 2: Run the focused test and verify compilation fails**

Run: `./mvnw --batch-mode --no-transfer-progress -Dtest=BuildInfoTest test`

Expected: FAIL because `BuildInfo(Map<String, String>, Properties)` does not exist.

- [ ] **Step 3: Implement environment-first metadata resolution**

Keep the public no-argument CDI constructor and delegate it:

```java
public BuildInfo() {
    this(System.getenv(), loadProperties());
}

BuildInfo(Map<String, String> environment, Properties properties) {
    this.version = value(environment, "APP_VERSION", properties, "git.build.version");
    this.commit = value(environment, "GIT_COMMIT", properties, "git.commit.id.abbrev");
}
```

Make `loadProperties()` static and use `BuildInfo.class.getResourceAsStream("/git.properties")`. Add a `value(...)` helper that ignores null or blank environment values before using the property or `dev` fallback.

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `./mvnw --batch-mode --no-transfer-progress -Dtest=BuildInfoTest test`

Expected: PASS with all `BuildInfoTest` methods green.

---

### Task 2: Stable Docker Compilation Layers

**Files:**
- Modify: `pom.xml`
- Modify: `Dockerfile`
- Modify: `Dockerfile.native`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Consumes: Existing Docker build arguments `APP_VERSION` and `GIT_COMMIT` supplied by CI.
- Produces: Maven project version `${revision}` with default `dev`; runtime container environment keys consumed by Task 1.

- [ ] **Step 1: Make the Maven project revision stable**

Change the project version and add its default property:

```xml
<version>${revision}</version>
```

```xml
<properties>
  <!-- Keep the compiled artifact version stable so metadata-only releases reuse Docker build layers. -->
  <revision>dev</revision>
```

Do not pass `-Drevision` from either Dockerfile or GitHub Actions.

- [ ] **Step 2: Remove release metadata from both build stages**

In `Dockerfile` and `Dockerfile.native`, replace `COPY src/ src/` with:

```dockerfile
COPY src/main/ src/main/
```

Delete the build-stage `ARG APP_VERSION`, `ARG GIT_COMMIT`, and `RUN printf ... git.properties` instructions. The Maven package/native commands must immediately follow source and generated-CSS copies without release-specific inputs.

- [ ] **Step 3: Stamp metadata in both final runtime stages**

Immediately before the final runtime `ENV` instruction in each Dockerfile, add:

```dockerfile
ARG APP_VERSION=dev
ARG GIT_COMMIT=dev
```

Extend the final runtime environment:

```dockerfile
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_PROFILE=prod \
    APP_VERSION=${APP_VERSION} \
    GIT_COMMIT=${GIT_COMMIT}
```

This keeps all dependency resolution, compilation, native-image generation, runtime packages, and artifact copies reusable; only the final metadata layer changes.

- [ ] **Step 4: Document the native fallback accurately**

Replace the `application.properties` comment above `quarkus.native.resources.includes` with:

```properties
# Bundle git.properties into the native image as the local-build fallback. Release containers
# provide the real footer version and commit through APP_VERSION/GIT_COMMIT. See web/BuildInfo.
quarkus.native.resources.includes=git.properties,fonts/*.ttf
```

- [ ] **Step 5: Validate the stable Maven build**

Run: `./mvnw --batch-mode --no-transfer-progress -DskipTests package`

Expected: PASS and produce the Quarkus application using the `dev` Maven revision.

- [ ] **Step 6: Inspect Docker cache boundaries**

Run:

```bash
rg -n "COPY src|ARG APP_VERSION|ARG GIT_COMMIT|mvnw.*package|ENV QUARKUS_HTTP_HOST" Dockerfile Dockerfile.native
```

Expected: `COPY src/main` precedes compilation; all `APP_VERSION`/`GIT_COMMIT` declarations occur only in runtime stages after compilation.

---

### Task 3: Tag-Derived Release Process

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `CLAUDE.md`
- Modify: `CONTRIBUTING.md`

**Interfaces:**
- Consumes: `GITHUB_REF`, `GITHUB_REF_NAME`, and `GITHUB_SHA` from GitHub Actions.
- Produces: `steps.buildmeta.outputs.version` equal to the semver tag without `v`, or `edge` on `main`; documented release process with no POM bump.

- [ ] **Step 1: Resolve image metadata from the Git ref**

Replace POM scraping in the `Resolve build metadata` step with:

```bash
if [[ "$GITHUB_REF" == refs/tags/v* ]]; then
  version="${GITHUB_REF_NAME#v}"
else
  version="edge"
fi
echo "version=$version" >> "$GITHUB_OUTPUT"
echo "commit=${GITHUB_SHA::7}" >> "$GITHUB_OUTPUT"
```

- [ ] **Step 2: Remove the obsolete tag/POM equality check**

Delete the complete `Verify tag matches pom version` step from the `release` job. The tag is now the only release-version source of truth.

- [ ] **Step 3: Update maintainer release documentation**

Update `CLAUDE.md` release guidance to state explicitly that maintainers must not bump `pom.xml`, because the tag supplies the release version and Maven uses stable `dev` for layer reuse.

Update `CONTRIBUTING.md` to state that the tag supplies the version displayed by release images and `pom.xml` remains at `dev`.

- [ ] **Step 4: Lint and inspect the workflow**

Run: `./actionlint .github/workflows/ci.yml`

Expected: no output and exit status 0.

Run:

```bash
rg -n "grep.*version|Verify tag matches pom|version=|APP_VERSION|GIT_COMMIT" .github/workflows/ci.yml
```

Expected: no POM version scraping or tag/POM guard; both Docker invocations still pass the resolved metadata.

- [ ] **Step 5: Run focused regression validation**

Run: `./mvnw --batch-mode --no-transfer-progress -Dtest=BuildInfoTest,FooterBuildInfoTest test`

Expected: PASS, proving local fallback remains nonblank and footer rendering still includes build information.

