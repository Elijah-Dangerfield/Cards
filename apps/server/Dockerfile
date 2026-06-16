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
COPY versions.properties gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./

# Copy only what :apps:server actually consumes:
#  - apps/server/ — the server module itself.
#  - libraries/{core,gameplay,bots}/ — shared game-engine + bot code (KMP
#    modules with a JVM target). The server runs the same GameEngine /
#    BotDecision code as the client so wire-format incompatibilities are
#    impossible; that's why these are shipped instead of duplicated.
#
# `-Dcards.serverOnly=true` (plumbed through settings.gradle.kts and the
# KMP convention plugin) trims the build graph to just these modules and
# skips the Android + iOS KMP targets so the image doesn't need the
# Android SDK / Kotlin Native toolchain.
#
# Adding a new :libraries:* dep to apps/server/build.gradle.kts will fail
# the Docker build at link time with "project not found". When that
# happens: (a) add the new lib's COPY line here, and (b) add its
# `include(":libraries:foo")` to settings.gradle.kts's always-included
# section. See apps/server/DEPLOY.md → "Server build slimming".
COPY apps/server/ apps/server/
COPY libraries/core/ libraries/core/
COPY libraries/gameplay/ libraries/gameplay/
COPY libraries/bots/ libraries/bots/

# kotlin.compiler.execution.strategy=in-process: run the Kotlin compiler inside
# the Gradle JVM instead of forking a separate Kotlin daemon. Otherwise the
# build holds TWO JVMs at once — Gradle (-Xmx4g) + the Kotlin daemon (-Xmx3g) =
# ~7GB — which the Fly build container intermittently OOM-kills mid-compile
# (the symptom: "Build failed with an exception" with an empty cause, because
# the daemon was SIGKILLed). In-process keeps it to a single JVM.
RUN chmod +x gradlew && \
    ./gradlew :apps:server:installDist \
        --no-daemon \
        --console=plain \
        --build-cache \
        -Dcards.serverOnly=true \
        -Dcards.skipGitHooksCheck=true \
        -Pkotlin.compiler.execution.strategy=in-process

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
