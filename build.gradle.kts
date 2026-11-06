plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "com.takehome"
version = "1.0.0"

description = """
Savings account API - Westpac take-home exercise.

POST /api/v1/accounts                 creates a savings account (validated request body)
GET  /api/v1/accounts/{id}            fetches a savings account (Redis-cached)
GET  /api/v1/accounts/by-number/{n}   fetches by public account number (Redis-cached)
"""

java {
    toolchain {
        // Java 21 LTS: records, sealed types, virtual threads (used for request handling).
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot's BOM governs every Spring artifact (managed versions only;
    // no versions are declared inline anywhere in this file - see libs.versions.toml).
    implementation(platform(libs.spring.boot.dependencies))

    // REST layer + bean validation (request-body validation lives here)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)

    // Persistence: JPA over a single Postgres table
    implementation(libs.spring.boot.starter.data.jpa)
    runtimeOnly(libs.postgresql.driver)

    // Caching: Redis-backed cache in front of GET reads
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.spring.boot.starter.data.redis)

    // Observability: health probes include DataSource/Redis readiness
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.data.jpa.test)
    testImplementation(libs.spring.boot.jdbc.test)
    testImplementation(libs.spring.boot.restclient) // RestTemplateBuilder moved out of core in Boot 4
    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Single deterministic boot artifact (the classic plain jar is disabled so the
// Dockerfile can COPY a fixed name without a two-jar glob surprise).
tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Fail fast on stuck integration tests instead of hanging the build.
    systemProperty("junit.jupiter.execution.timeout.default", "60 s")
    testLogging {
        events("passed", "skipped", "failed")
        showStackTraces = true
        showExceptions = true
    }
}
