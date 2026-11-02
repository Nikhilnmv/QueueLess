# Builds QueueLess into one container: the React app is compiled and packaged inside the
# Spring Boot jar, so a single process serves the pages and the API.
# Render runs this file automatically when you deploy (see render.yaml).

# 1) Build the React frontend
FROM node:24-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) Build the Spring Boot jar (pom.xml copies ../frontend/dist into the jar)
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build/backend
COPY backend/ ./
COPY --from=frontend /build/frontend/dist /build/frontend/dist
RUN mvn -B -q -DskipTests package

# 3) Run the jar
FROM eclipse-temurin:21-jre-alpine
COPY --from=backend /build/backend/target/queueless.jar /app/queueless.jar
ENV SPRING_PROFILES_ACTIVE=prod
# Keeps memory well inside Render's free 512 MB instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
CMD ["java", "-jar", "/app/queueless.jar"]
