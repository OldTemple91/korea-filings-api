plugins {
    java
    id("org.springframework.boot") version "3.4.0"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.dartintel"
version = "0.0.1-SNAPSHOT"

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

    // OpenAPI / Swagger UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Observability (Prometheus metrics from Actuator)
    implementation("io.micrometer:micrometer-registry-prometheus")

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names")

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
    listOf("GEMINI_API_KEY", "OPENAI_API_KEY", "DART_API_KEY").forEach { name ->
        System.getenv(name)?.also { environment(name, it) }
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("dartintel-api.jar")
}
