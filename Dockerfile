# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

# --- CSS stage: compile Tailwind + daisyUI with Bun (no JS ships at runtime) ---
FROM oven/bun:1@sha256:5ff609364c049b54eb0ff560ec96319729a972078ef2c755d758f0c6ef89c2d6 AS css
WORKDIR /app
COPY package.json bun.lock ./
RUN --mount=type=cache,target=/root/.bun/install/cache \
    bun install --frozen-lockfile --ignore-scripts
# Templates are needed so Tailwind's @source can scan them for class names.
COPY src/main/css/ src/main/css/
COPY src/main/resources/templates/ src/main/resources/templates/
RUN bun run css:build
# Output: /app/src/main/resources/META-INF/resources/calit.css

# --- Build stage: BellSoft Liberica JDK 25 + the Maven wrapper (no Maven in the image) ---
FROM bellsoft/liberica-runtime-container:jdk-26-musl@sha256:0a761cfa560e4a9358d4ca584fe399cf2f1164fd0c913d2e363efe7fd0e434b3 AS build
WORKDIR /build

# Warm the dependency cache on the POM first so source-only edits don't re-download everything.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests dependency:go-offline

# Build the Quarkus fast-jar. Tests are skipped here: they rely on Quarkus Dev Services
# (a Docker-managed Postgres), which is not available inside this build. Run `./mvnw test`
# on the host (with Docker running) before building the image.
COPY src/ src/
# Overlay the Bun-compiled stylesheet (gitignored, so not in the COPY above).
COPY --from=css /app/src/main/resources/META-INF/resources/calit.css src/main/resources/META-INF/resources/calit.css
# Inject build metadata so the footer shows the real version + commit instead of "dev".
# The git-commit-id plugin skips silently (failOnNoGitDirectory=false) when .git is absent;
# this hand-written file is what ends up on the classpath inside the jar.
ARG APP_VERSION=dev
ARG GIT_COMMIT=dev
RUN printf 'git.build.version=%s\ngit.commit.id.abbrev=%s\n' "$APP_VERSION" "$GIT_COMMIT" \
    > src/main/resources/git.properties
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests clean package

# --- Font-stack donor stage: the hardened runtime base below has no shell and no apk, so the
# freetype/fontconfig stack AWT card rendering needs must be COPYed in from an image that has
# them. Reuses the jdk-26-musl image the build stage already pulls, so no new image enters the
# build (already pinned above, already layer-cached).
FROM bellsoft/liberica-runtime-container:jdk-26-musl@sha256:0a761cfa560e4a9358d4ca584fe399cf2f1164fd0c913d2e363efe7fd0e434b3 AS fontstack
# Package versions deliberately UNPINNED here. Pinning the base image above by digest does NOT
# pin these apk packages -- apk add resolves against the live Alpaquita repo at build time
# regardless. Left floating on purpose: freetype/fontconfig are CVE-rich C font parsers, so
# picking up the patched version on every rebuild is the safer default. Both failure modes this
# could introduce are already gated in CI before any GHCR push -- Trivy (HIGH,CRITICAL,
# exit-code 1, ignore-unfixed: true) catches a vulnerable-with-a-fix version, and the smoke test
# (requests /og.png and /og/admin.png, checks PNG magic + non-identity) catches a functionally
# broken font stack. Renovate also can't manage an apk pin here -- no native Dockerfile-apk
# manager, and Alpaquita isn't a standard repology datasource -- so a pin would just rot.
RUN apk add --no-cache freetype fontconfig font-dejavu-core \
    # Populate /var/cache/fontconfig at build time, as root, so it can be copied pre-warmed into
    # the runtime stage below (UID 1001 there can't run fc-cache itself -- no apk, no shell).
    && fc-cache -f

# --- Runtime stage: BellSoft hardened distroless musl runtime container (production) ---
# JRE 26 runs the JDK-25-compiled fast-jar fine (forward-compatible); pure-bytecode app, so the
# musl libc is a non-issue. This base has no shell, no package manager, and no CVE-fixing distro
# packages beyond what BellSoft ships -- it is the hardened/distroless target from calit-gabg.
FROM bellsoft/hardened-liberica-runtime-container:jre-distroless-musl@sha256:3d57f2eff627ae1ae3730b070caa9cd6f129a718befd447c283ef78d73d37445 AS runtime
WORKDIR /app

# This base ships libfontmanager.so but not the libfreetype.so.6 it links against, and no font,
# no fontconfig, and no /etc/fonts at all -- so AWT card rendering fails at request time without
# them. See Dockerfile.native for why the font package is required, not decorative. There is no
# apk here, so the stack is COPYed from the fontstack donor stage above instead of installed.
# Only the unversioned SONAME is copied for each lib (e.g. libfreetype.so.6, not
# libfreetype.so.6.20.6): that SONAME is the only name libfontmanager.so's NEEDED entries
# reference (per `readelf -d`), and BuildKit COPY dereferences a symlink source into a full
# regular file at the destination -- it does not leave a dangling link. Copying the versioned
# filename too would just be dead weight that an Alpine package bump in the donor could break.
COPY --from=fontstack --chown=1001:1001 \
    /usr/lib/libfreetype.so.6 \
    /usr/lib/libfontconfig.so.1 \
    /usr/lib/libexpat.so.1 \
    /usr/lib/libbz2.so.1 \
    /usr/lib/libpng16.so.16 \
    /usr/lib/libbrotlidec.so.1 \
    /usr/lib/libbrotlicommon.so.1 \
    /usr/lib/
COPY --from=fontstack --chown=1001:1001 /etc/fonts/ /etc/fonts/
COPY --from=fontstack --chown=1001:1001 /usr/share/fonts/ /usr/share/fonts/
# Pre-warmed by `fc-cache -f` in the donor stage: UID 1001 has no apk/shell here to build it
# itself, and (per calit's own history) couldn't write a fresh one to this path anyway.
COPY --from=fontstack --chown=1001:1001 /var/cache/fontconfig/ /var/cache/fontconfig/
# Font.createFont(InputStream) spills to a temp file, so a read-only root filesystem needs a
# tmpfs mount at /tmp or card rendering fails at request time regardless of the font stack.

# Quarkus fast-jar layout: copy the four pieces in cache-friendly order.
# Files are owned by the non-root runtime user (SEC-DEP-05); the fast-jar is read-only at
# runtime so the app needs no write access to /app.
COPY --chown=1001:1001 --from=build /build/target/quarkus-app/lib/ lib/
COPY --chown=1001:1001 --from=build /build/target/quarkus-app/*.jar ./
COPY --chown=1001:1001 --from=build /build/target/quarkus-app/app/ app/
COPY --chown=1001:1001 --from=build /build/target/quarkus-app/quarkus/ quarkus/

# Run as a non-root numeric UID (SEC-DEP-05). A numeric UID needs no /etc/passwd entry.
USER 1001

EXPOSE 8080
# Bind to all interfaces inside the container; %prod profile is the deployment default.
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_PROFILE=prod
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
