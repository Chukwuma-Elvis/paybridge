# syntax=docker/dockerfile:1.7
# ==============================================================================
# PayBridge production image (Spring Boot 4 / Java 25 / Maven), built for Render.
# Follows the agent-docker-generator playbook; deviations are explained where they occur.
#
# Pipeline:  builder (compile)  ->  optimizer (unpack + custom JRE)  ->  trainer (CDS archive)  ->  runner (tiny final image)
# ==============================================================================

# Pinned to exact patch versions (never :latest) so builds are reproducible and the CDS archive stays valid.
ARG TEMURIN_JDK=eclipse-temurin:25.0.4.1_1-jdk-alpine-3.24
ARG RUNTIME_BASE=alpine:3.24.2

# ==============================================================================
# STAGE 1: Dependency resolution & compilation with BuildKit cache mounts
# ==============================================================================
FROM ${TEMURIN_JDK} AS builder
WORKDIR /build

# Copy only the build wrapper and dependency list first, so the (slow) dependency download is cached
# until pom.xml itself changes.
COPY pom.xml mvnw ./
COPY .mvn ./.mvn
RUN chmod +x ./mvnw

# The cache mount keeps ~/.m2 between builds without bloating any image layer.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw dependency:go-offline -B

# Source is copied last: editing code does not invalidate the dependency layer.
# -Dmaven.test.skip=true skips compiling tests too (tests run in CI, not in the image build).
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw package -Dmaven.test.skip=true -B

# ==============================================================================
# STAGE 2: Unpack the jar and build a stripped custom JRE with jlink
# ==============================================================================
FROM ${TEMURIN_JDK} AS optimizer
WORKDIR /build

COPY --from=builder /build/target/*.jar app.jar

# Playbook uses `extract --layers --launcher` + JarLauncher. For Spring Boot 4 we use the DEFAULT extract output
# (app.jar + lib/). Reason: a CDS (class-data-sharing) archive only covers classes loaded by the normal JVM class
# loader, and JarLauncher loads application classes through its own loader, so the archive would silently not
# apply. `-jar app.jar` with a Class-Path to lib/ is the layout Spring's own CDS guide uses.
# lib/ (rarely changes) and app.jar (changes every build) are still separate layers in the final image.
RUN java -Djarmode=tools -jar app.jar extract --destination /build/extracted

# jdeps works out which Java modules the app needs; jlink then builds a JRE containing only those.
# SAFETY: jdeps cannot see reflection, so these are always added: jdk.unsupported (Netty/Unsafe),
# java.management + jdk.management (Actuator/Micrometer metrics), java.naming (DNS/JNDI),
# java.security.jgss (database Kerberos/SSL). (jdk.crypto.ec is part of java.base on Java 22+.)
# --generate-cds-archive gives the custom JRE its own base CDS archive, which the application archive in the next
# stage builds on top of.
RUN JDEPS_MODULES=$(jdeps \
      --ignore-missing-deps \
      --multi-release 25 \
      --print-module-deps \
      --class-path '/build/extracted/lib/*' \
      /build/extracted/app.jar 2>/dev/null || echo "java.base,java.sql,java.net.http,java.desktop") \
    && echo "jlink modules: ${JDEPS_MODULES}" \
    && jlink \
      --add-modules ${JDEPS_MODULES},jdk.unsupported,java.management,jdk.management,java.naming,java.security.jgss \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --generate-cds-archive \
      --output /custom-jre

# ==============================================================================
# STAGE 3: CDS training run (an archive of the loaded classes makes startup faster)
# ==============================================================================
FROM ${RUNTIME_BASE} AS trainer
WORKDIR /app

# libstdc++ is the only extra library the JVM needs on Alpine (the JRE is built for Alpine's own libc, so no gcompat).
RUN apk add --no-cache libstdc++

COPY --from=optimizer /custom-jre /custom-jre
COPY --from=optimizer /build/extracted/lib/ ./lib/
COPY --from=optimizer /build/extracted/app.jar ./app.jar

ENV JAVA_HOME=/custom-jre
ENV PATH="/custom-jre/bin:$PATH"

# Boots the application just far enough to load its classes, then exits (spring.context.exit=onRefresh).
# There is no database at build time, so the database-dependent pieces are switched off for this run ONLY:
# Flyway off, no schema check, no connection attempt at start-up. None of this reaches the final image.
# The command line must match the real start-up exactly (same -jar path and working directory), or the JVM
# quietly ignores the archive. If training fails, the build FAILS on the COPY in the runner stage (no silent fallback).
RUN java -XX:ArchiveClassesAtExit=/app/application.jsa \
         -Dspring.context.exit=onRefresh \
         -Dspring.flyway.enabled=false \
         -Dspring.jpa.hibernate.ddl-auto=none \
         -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
         -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
         -Dspring.datasource.url=jdbc:postgresql://localhost:5432/training \
         -Dspring.datasource.username=training \
         -Dspring.datasource.password=training \
         -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
         -Dpaybridge.remittance.processor.enabled=false \
         -Dpaybridge.settlement.closer.enabled=false \
         -Dpaybridge.dispatcher.enabled=false \
         -Dpaybridge.reconciler.enabled=false \
         -Dpaybridge.fx.refresher.enabled=false \
         -jar /app/app.jar

# ==============================================================================
# STAGE 4: Hardened production runtime image (this is what Render runs)
# ==============================================================================
FROM ${RUNTIME_BASE} AS runner
WORKDIR /app

# 1. Security: a dedicated unprivileged user with numeric IDs (orchestrators check numeric IDs, not names).
#    Only libstdc++ is installed: no compilers, git, curl, GNU wget, tzdata (Java carries its own time-zone data),
#    and the healthcheck uses BusyBox's built-in wget.
RUN addgroup -S appgroup -g 10001 && \
    adduser -S appuser -u 10001 -G appgroup -h /app -s /sbin/nologin && \
    apk add --no-cache libstdc++

# 2. Custom runtime
COPY --from=optimizer --chown=10001:10001 /custom-jre /custom-jre

# 3. Start script (changes rarely). Line endings forced to LF so a Windows checkout cannot break it.
COPY --chown=10001:10001 docker-entrypoint.sh /app/docker-entrypoint.sh
RUN sed -i 's/\r$//' /app/docker-entrypoint.sh && chmod 755 /app/docker-entrypoint.sh

# 4. Application, ordered from least to most volatile: libraries, then the app, then the CDS archive built from them.
COPY --from=optimizer --chown=10001:10001 /build/extracted/lib/ ./lib/
COPY --from=optimizer --chown=10001:10001 /build/extracted/app.jar ./app.jar
COPY --from=trainer   --chown=10001:10001 /app/application.jsa ./application.jsa

# 5. Environment. Memory is set by percentage so the JVM respects whatever limit the host gives the container
#    (Render Starter is 512 MB). A 25% starting heap keeps small plans from reserving half their memory up front.
ENV JAVA_HOME=/custom-jre \
    PATH="/custom-jre/bin:$PATH" \
    JAVA_OPTS="-XX:SharedArchiveFile=/app/application.jsa \
               -XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=25.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom \
               -Dserver.shutdown=graceful"

# 6. Run as the unprivileged user; SIGTERM lets the app finish in-flight requests before stopping.
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM

# 7. Health check with a long start period: on a half-CPU host the app takes about two minutes to start, and a probe
#    that fails during start-up must not count against it. /actuator/health is public and is the same path Render uses.
HEALTHCHECK --interval=15s --timeout=3s --start-period=150s --retries=3 \
  CMD wget -qO- http://localhost:${PORT:-8080}/actuator/health || exit 1

# 8. Exec-form entrypoint: the script ends with `exec java ...`, so the JVM is PID 1 and receives SIGTERM directly.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
