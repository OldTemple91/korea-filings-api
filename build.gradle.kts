plugins {
    java
    id("org.springframework.boot") version "3.4.7"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.dartintel"
version = "0.4.0"

// Override Spring Boot 3.4 BOM's Testcontainers (1.20.4) so the bundled
// docker-java client can negotiate API 1.44+ that Docker Engine 29 requires.
extra["testcontainers.version"] = "1.21.3"

// Testcontainers 1.21.x still ships docker-java 3.4.2 whose default API
// version is rejected by Docker Engine 29. Force all docker-java artifacts
// to the latest 3.5.1 so the client speaks a version the daemon accepts.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.github.docker-java") {
            useVersion("3.5.1")
            because("Docker Engine 29 requires API >= 1.44; docker-java 3.4.x defaults too low")
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring core
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // for WebClient only
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Resilience
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    // OpenAPI / Swagger UI — 2.7+ targets Spring Boot 3.4 / Spring 6.2
    // (2.6.x errors with NoSuchMethodError on ControllerAdviceBean).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    // JJWT — needed only for the Coinbase CDP facilitator JWT (Ed25519).
    // The public x402.org testnet facilitator does not require auth; this
    // dependency is dormant until the mainnet env vars are populated.
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Observability (Prometheus metrics from Actuator)
    implementation("io.micrometer:micrometer-registry-prometheus")

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names")

    // HTML / XBRL text extraction for DART /api/document.xml ZIP bodies.
    // jsoup over a hand-rolled regex stripper because filing bodies
    // mix XBRL XML, HTML tables, and inline SVG / iframes — jsoup
    // handles malformed nested markup that ad-hoc regex would
    // misparse. ZIP unpacking uses Java's built-in ZipInputStream so
    // no separate compress library is needed.
    implementation("org.jsoup:jsoup:1.18.3")

    // Lombok (used sparingly per CLAUDE.md)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("com.github.tomakehurst:wiremock-standalone:3.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // docker-java defaults to API v1.32, which Docker Engine 29+ rejects
    // (it requires v1.44+). Push the override through every property key
    // docker-java is known to consult so the URL gets a /v1.44/ prefix.
    environment("DOCKER_API_VERSION", "1.44")
    systemProperty("DOCKER_API_VERSION", "1.44")
    systemProperty("api.version", "1.44")
    systemProperty("docker.api.version", "1.44")
    // Docker Desktop's auto-detected socket lives in a sandboxed path that
    // cannot be bind-mounted into Ryuk; redirect the in-container socket
    // mount to the symlinked /var/run/docker.sock that the daemon accepts.
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")

    // Forward optional vendor credentials from the host shell to live
    // integration tests gated by @EnabledIfEnvironmentVariable. Tests stay
    // skipped when the variable is absent.
    listOf("GEMINI_API_KEY", "DART_API_KEY").forEach { name ->
        System.getenv(name)?.also { environment(name, it) }
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("dartintel-api.jar")
}

// Populate /actuator/info with build metadata — version, group, name.
// The Spring Boot Gradle plugin supplies the task; we just enable it.
//
// `time.set(null)` suppresses the wall-clock build timestamp so two
// builds from the same source tree produce byte-for-byte identical
// JARs. Without this the build is non-deterministic, which defeats
// Docker layer caching and digest-based image verification.
springBoot {
    buildInfo {
        excludes.add("time")
        properties {
            additional.set(
                mapOf(
                    "description" to "Korean DART corporate filings AI summary API",
                )
            )
        }
    }
}
