# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml first so Maven dependency download is cached separately
# from source changes. Only re-downloads deps when pom.xml changes.
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q
# Output: target/*.jar

# ── Stage 2: Run ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# All runtime config comes from environment variables.
# Spring Boot reads SPRING_* env vars automatically.
# Additional app-specific vars (e.g. ALLOWED_ORIGIN) are read
# via System.getenv() in WebConfig and WebSocketConfig.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
