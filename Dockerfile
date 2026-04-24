# syntax=docker/dockerfile:1.7

# ────────────────── Stage 1: build the layered jar ──────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

# Cache Gradle distribution + dependencies across rebuilds by copying
# the wrapper + build files first and triggering a dependency-only task.
COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon --version

COPY src src
RUN ./gradlew --no-daemon bootJar

# Explode the jar into layers so the final image's file-system layout
# matches Spring Boot's layered-jar conventions. layertools produces
# dependencies/, spring-boot-loader/, snapshot-dependencies/, application/
# in the current working directory.
RUN java -Djarmode=layertools -jar build/libs/dartintel-api.jar extract

# ────────────────── Stage 2: minimal runtime image ──────────────────
FROM eclipse-temurin:21-jre

# Create an unprivileged user. Running the JVM as root is an easy escalation
# path if the container is ever compromised.
RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --home-dir /app --shell /sbin/nologin app

WORKDIR /app

# Copy layered jar contents from the builder. The order (deps → loader →
# snapshots → application) produces the smallest rebuild deltas.
COPY --from=builder --chown=app:app /workspace/dependencies/            ./
COPY --from=builder --chown=app:app /workspace/spring-boot-loader/      ./
COPY --from=builder --chown=app:app /workspace/snapshot-dependencies/   ./
COPY --from=builder --chown=app:app /workspace/application/             ./

USER app
EXPOSE 8080

# -XX:MaxRAMPercentage caps heap to 75% of the container's memory limit so
# Hikari / Netty / native buffers have room to breathe.
# -XX:+ExitOnOutOfMemoryError makes the container restart on OOM rather than
# limping along in a degraded state.
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-Dfile.encoding=UTF-8", \
            "org.springframework.boot.loader.launch.JarLauncher"]
