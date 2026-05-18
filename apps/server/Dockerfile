# --------------------------------------------------------------------------
# Build stage: full Gradle build of just :apps:server, producing the
# distZip artifact (bin script + lib jars).
#
# We use Temurin 17 (matches `kotlin { jvmToolchain(17) }` in the server's
# build.gradle.kts). Keeping the build-stage image close to the runtime
# stage's base reduces oddity around glibc / JVM differences.
#
# --no-daemon: containers are ephemeral, daemon-mode is pure overhead.
# --console=plain: cleaner CI/Docker logs.
# --build-cache: small wins on incremental rebuilds within a single layer.
# --------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

# Copy build-essentials first so Gradle init can cache.
COPY gradle/ gradle/
COPY build-logic/ build-logic/
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./

# Copy only what :apps:server actually depends on. server is self-contained
# (no :libraries / :features deps), so we don't need the rest of the repo.
COPY apps/server/ apps/server/

RUN chmod +x gradlew && \
    ./gradlew :apps:server:installDist \
        --no-daemon \
        --console=plain \
        --build-cache \
        -Dcards.skipGitHooksCheck=true

# --------------------------------------------------------------------------
# Runtime stage: JRE-only, copy the installDist output, run.
#
# Setting JAVA_OPTS at the Dockerfile level so any inheritor can override
# them via Fly's `[env]` block in fly.toml.
# --------------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /workspace/apps/server/build/install/server/ /app/

ENV JAVA_OPTS=""
ENV SERVER_PORT=8080
EXPOSE 8080

# `bin/server` is the start script Gradle's application plugin generates.
CMD ["/app/bin/server"]
