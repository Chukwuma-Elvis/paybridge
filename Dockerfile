# syntax=docker/dockerfile:1.7
# ==============================================================================
# STAGE 1: Dependency resolution & compilation with BuildKit Cache Mounts
# ==============================================================================
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build

# Copy build wrappers and dependency descriptors first
COPY pom.xml mvnw ./
COPY .mvn ./.mvn
RUN chmod +x ./mvnw

# Leverage BuildKit cache mount to preserve ~/.m2 between builds without bloating image layers
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw dependency:go-offline -B

# Copy application source code and package
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw clean package -DskipTests -B

# ==============================================================================
# STAGE 2: Layer Extraction & jlink Custom JRE Creation
# ==============================================================================
FROM eclipse-temurin:25-jdk-alpine AS optimizer
WORKDIR /build

COPY --from=builder /build/target/*.jar app.jar

# 2a. Extract Spring Boot Layers (Spring Boot 4 / 3.3+ syntax)
RUN java -Djarmode=tools -jar app.jar extract --destination /build/extracted

# 2b. Analyze dependencies with jdeps and build a custom stripped JRE
# SAFETY: Always manually append jdk.unsupported (Netty/Unsafe), java.naming (DNS/JNDI),
# java.management (Metrics/Actuator), and java.security.jgss (Database Kerberos/SSL)
RUN JDEPS_MODULES=$(jdeps \
      --ignore-missing-deps \
      --multi-release 25 \
      --print-module-deps \
      --class-path '/build/extracted/dependencies/BOOT-INF/lib/*' \
      app.jar 2>/dev/null || echo "java.base,java.sql,java.net.http,java.desktop") \
    && jlink \
      --add-modules ${JDEPS_MODULES},jdk.unsupported,java.management,java.naming,java.security.jgss \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /custom-jre

# ==============================================================================
# STAGE 3: CDS (Class Data Sharing) Training Run
# ==============================================================================
FROM alpine:3.20.3 AS trainer
WORKDIR /app

# Minimal C libraries required by the stripped JRE
RUN apk add --no-cache libstdc++ gcompat

COPY --from=optimizer /custom-jre /custom-jre
COPY --from=optimizer /build/extracted/dependencies/ ./
COPY --from=optimizer /build/extracted/spring-boot-loader/ ./
COPY --from=optimizer /build/extracted/snapshot-dependencies/ ./
COPY --from=optimizer /build/extracted/application/ ./

ENV JAVA_HOME=/custom-jre
ENV PATH="/custom-jre/bin:$PATH"

# Perform CDS training run: boots JVM, pre-computes metadata, and cleanly exits on refresh
RUN java -XX:ArchiveClassesAtExit=/app/application.jsa \
         -Dspring.context.exit=onRefresh \
         -cp ".:BOOT-INF/classes:BOOT-INF/lib/*" \
         org.springframework.boot.loader.launch.JarLauncher 2>/dev/null || true

# ==============================================================================
# STAGE 4: Hardened Production Runtime Image for Render
# ==============================================================================
FROM alpine:3.20.3 AS runner
WORKDIR /app

# 1. Security: Create dedicated unprivileged user with numeric UID/GID
RUN addgroup -S appgroup -g 10001 && \
    adduser -S appuser -u 10001 -G appgroup -h /app -s /sbin/nologin && \
    apk add --no-cache libstdc++ gcompat tzdata wget

# 2. Copy stripped custom runtime
COPY --from=optimizer --chown=10001:10001 /custom-jre /custom-jre

# 3. Copy layers ordered by volatility (least volatile -> most volatile)
COPY --from=optimizer --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=optimizer --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=optimizer --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=optimizer --chown=10001:10001 /build/extracted/application/ ./

# 4. Copy CDS training archive
COPY --from=trainer --chown=10001:10001 /app/application.jsa ./application.jsa

# 5. Environment & Ergonomics for Container & Render Starter Tiers
ENV JAVA_HOME=/custom-jre \
    PATH="/custom-jre/bin:$PATH" \
    JAVA_OPTS="-XX:SharedArchiveFile=/app/application.jsa \
               -XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom \
               -Dserver.shutdown=graceful"

# 6. Enforce Non-Root Execution & Signals
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM

# 7. Container-Aware Healthcheck with Start Period (probes dynamic Render $PORT)
HEALTHCHECK --interval=15s --timeout=3s --start-period=25s --retries=3 \
  CMD wget -qO- http://localhost:${PORT:-8080}/actuator/health || exit 1

# 8. Exec form ENTRYPOINT: auto-adapts DATABASE_URL to jdbc: format and binds to Render $PORT
ENTRYPOINT ["sh", "-c", "\
  if [ -n \"$DATABASE_URL\" ] && [ -z \"$SPRING_DATASOURCE_URL\" ]; then \
    export SPRING_DATASOURCE_URL=$(echo \"$DATABASE_URL\" | sed -e 's|^postgres://|jdbc:postgresql://|' -e 's|^postgresql://|jdbc:postgresql://|'); \
  elif [ -n \"$SPRING_DATASOURCE_URL\" ]; then \
    export SPRING_DATASOURCE_URL=$(echo \"$SPRING_DATASOURCE_URL\" | sed -e 's|^postgres://|jdbc:postgresql://|' -e 's|^postgresql://|jdbc:postgresql://|'); \
  fi; \
  exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} org.springframework.boot.loader.launch.JarLauncher\
"]
