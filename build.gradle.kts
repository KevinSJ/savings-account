plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.takehome"
version = "1.0.0"

description = """
Savings account API - Westpac take-home exercise.

POST /api/v1/accounts   creates a savings account (validated request body)
GET  /api/v1/accounts/{id}  fetches a savings account (Redis-cached)
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
    // REST layer + bean validation (request-body validation lives here)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Persistence: JPA over a single Postgres table
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    // Caching: Redis-backed cache in front of GET reads
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Observability: health probes include DataSource/Redis readiness
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
