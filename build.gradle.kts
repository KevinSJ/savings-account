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

    // OpenAPI: the contract in openapi/ is generated from these annotations,
    // never hand-maintained (see tasks.generateOpenApi below).
    implementation(libs.springdoc.openapi.starter.webmvc.api)

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

// Regenerates openapi/savings-accounts-api.yaml from the running application.
// The generated document is the contract; it is never edited by hand.
tasks.register<Exec>("generateOpenApi") {
    group = "documentation"
    description = "Regenerates openapi/savings-accounts-api.yaml from the application (needs Docker)."
    dependsOn(tasks.bootJar)
    environment("SKIP_BUILD", "1")
    commandLine("bash", "scripts/generate-openapi.sh")
}

// Fails when the committed contract is stale. "Generated, so it cannot drift" is
// only true if something checks: regenerating is discipline, this is the gate.
// Wire it up in CI (it needs Docker, because the document is rendered by a
// running instance). Deliberately not part of `check`: that would put a container
// startup in the inner loop.
tasks.register<Exec>("verifyOpenApi") {
    group = "verification"
    description = "Regenerates the contract and fails if the committed file differs."
    dependsOn(tasks.bootJar)
    environment("SKIP_BUILD", "1")
    commandLine("bash", "-c", "scripts/generate-openapi.sh && git diff --exit-code -- openapi/")
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
