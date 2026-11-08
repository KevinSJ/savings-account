# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage: official Gradle image (Alpine + JDK 21 + Gradle already
# installed), so the image build never downloads a Gradle distribution from the
# network. Keep this tag in sync with gradle/wrapper/gradle-wrapper.properties.
# ---------------------------------------------------------------------------
FROM gradle:9.8.2-jdk21-alpine AS build
WORKDIR /workspace

# Copy only the build definition first so dependency layers stay cached
# across source-only changes (classic Docker layer-order discipline).
# gradle/ supplies libs.versions.toml, the version catalog the build relies on.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/

# A warm-up run: resolves everything the project needs. Cheap after the first
# push, decisive for CI cache hits afterwards.
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

COPY src/ src/
# --no-daemon: single-shot build, no daemon to leave behind.
# -x test: image builds publish code that the CI suite has gated; keeping test
# execution here would make the image build double as a flaky test runner.
RUN gradle --no-daemon bootJar -x test

# ---------------------------------------------------------------------------
# Runtime stage: JRE only, layered jar (fewer bytes shipped per release),
# non-root user, JVM sized for containers by default.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S spring \
    && adduser -S spring -G spring

# Boot's jar is laid out in layers; `jarmode=tools extract` produces a thin
# launcher jar plus lib/ (framework dependencies). Copying lib as its own layer
# means dependency-only rebuilds ship no new bytes.
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted \
    && rm app.jar \
    && mv extracted/app.jar . \
    && mv extracted/lib . \
    && rm -rf extracted

USER spring

HEALTHCHECK --interval=10s --timeout=3s --retries=12 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
