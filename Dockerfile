# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy pom.xml first so Maven dependency download is cached separately
COPY java-server/pom.xml .
RUN mvn dependency:go-offline -q 2>/dev/null || true

# Build the fat JAR
COPY java-server/src ./src
RUN mvn package -q -DskipTests

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /build/target/techconnect-server-1.0.0.jar app.jar
COPY index.html index.html

# STATIC_DIR: where index.html lives (this folder)
# DB_PATH: SQLite file — override with a Railway volume path to persist data
ENV STATIC_DIR=/app
ENV DB_PATH=/data/techconnect.sqlite

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
