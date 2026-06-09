# syntax=docker/dockerfile:1

# --- Build stage: BellSoft Liberica JDK 25 + the Maven wrapper (no Maven in the image) ---
FROM bellsoft/liberica-runtime-container:jdk-26-musl AS build
WORKDIR /build

# Warm the dependency cache on the POM first so source-only edits don't re-download everything.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q -DskipTests dependency:go-offline

# Build the Quarkus fast-jar. Tests are skipped here: they rely on Quarkus Dev Services
# (a Docker-managed Postgres), which is not available inside this build. Run `./mvnw test`
# on the host (with Docker running) before building the image.
COPY src/ src/
RUN ./mvnw -B -q -DskipTests clean package

# --- Runtime stage: BellSoft minimal musl runtime container (production) ---
# JRE 26 runs the JDK-25-compiled fast-jar fine (forward-compatible); pure-bytecode app, so the
# musl libc is a non-issue. The runtime-container image is purpose-built minimal for production.
FROM bellsoft/liberica-runtime-container:jre-26-musl AS runtime
WORKDIR /app

# Quarkus fast-jar layout: copy the four pieces in cache-friendly order.
COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar ./
COPY --from=build /build/target/quarkus-app/app/ app/
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/

EXPOSE 8080
# Bind to all interfaces inside the container; %prod profile is the deployment default.
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_PROFILE=prod
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
