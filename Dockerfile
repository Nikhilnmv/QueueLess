# syntax=docker/dockerfile:1
#
# QueueLess production image: one container that serves the React build and the API
# from a single origin (no CORS, same-site session cookies, same-origin SSE).
#
#   docker build -t queueless .
#   docker run -p 8080:8080 -e DATABASE_URL=... -e DATABASE_USERNAME=... -e DATABASE_PASSWORD=... queueless
#
# Tests are not run here (the image build has no database); CI runs them against PostgreSQL.

# ---- 1. Frontend: TypeScript check + Vite production build -------------------------------
FROM node:24-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. Backend: Spring Boot jar with frontend/dist packaged as static resources ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
# pom.xml packages ../frontend/dist into classpath:/static
COPY --from=frontend /build/frontend/dist /build/frontend/dist
RUN mvn -B -q -DskipTests package

# ---- 3. Runtime: small JRE image, non-root user, class-data-sharing archive --------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S queueless && adduser -S queueless -G queueless
WORKDIR /app

# Memory settings for a 512 MB instance (Render free): about 205 MB heap, capped metaspace and
# code cache, serial GC and C1-only JIT (less memory and CPU on a 0.1-CPU instance).
# Measured locally: ~320 MB resident under the smoke test. Override JAVA_TOOL_OPTIONS to tune.
ENV TZ=UTC \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

COPY --from=backend /build/backend/target/queueless.jar /tmp/queueless.jar

# Extract the jar (faster class loading) and record a CDS archive with a training run that
# stops right after the Spring context refreshes. The training run needs no database: Flyway,
# schema validation and JDBC metadata access are switched off for it only. Locally this cut
# startup time by ~40%, which matters for cold starts on a slow free instance.
RUN java -Djarmode=tools -jar /tmp/queueless.jar extract --destination /app \
 && rm /tmp/queueless.jar \
 && java -XX:ArchiveClassesAtExit=/app/app.jsa -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false \
      -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:1/cds-training \
      -jar /app/queueless.jar \
 && chown -R queueless:queueless /app

USER queueless
EXPOSE 8080
# Spring Boot listens on $PORT (Render sets 10000) or 8080.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/app/app.jsa", "-jar", "/app/queueless.jar"]
