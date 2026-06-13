# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

# --- CSS stage: compile Tailwind + daisyUI with Bun (no JS ships at runtime) ---
FROM oven/bun:1@sha256:e10577f0db68676a7024391c6e5cb4b879ebd17188ab750cf10024a6d700e5c4 AS css
WORKDIR /app
COPY package.json bun.lock ./
RUN --mount=type=cache,target=/root/.bun/install/cache \
    bun install --frozen-lockfile
# Templates are needed so Tailwind's @source can scan them for class names.
COPY src/main/css/ src/main/css/
COPY src/main/resources/templates/ src/main/resources/templates/
RUN bun run css:build
# Output: /app/src/main/resources/META-INF/resources/calit.css

# --- Build stage: BellSoft Liberica JDK 25 + the Maven wrapper (no Maven in the image) ---
FROM bellsoft/liberica-runtime-container:jdk-26-musl@sha256:39e4affaa404bc8d10fd8824587399969f834b966583cf72d7e4fba9a258d653 AS build
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
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests clean package

# --- Runtime stage: BellSoft minimal musl runtime container (production) ---
# JRE 26 runs the JDK-25-compiled fast-jar fine (forward-compatible); pure-bytecode app, so the
# musl libc is a non-issue. The runtime-container image is purpose-built minimal for production.
FROM bellsoft/liberica-runtime-container:jre-26-musl@sha256:402c4eab1858b2ef7c4863f48a927850fef6b562e08803a75d63ded195c6c87b AS runtime
WORKDIR /app

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
