# ── Stage 1: Build the JAR ───────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY java-server/pom.xml .
COPY java-server/src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Run ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# JAR
COPY --from=build /build/target/techconnect-server-1.0.0.jar app.jar

# Static frontend files
COPY index.html          ./static/index.html
COPY database.js         ./static/database.js
COPY import-profiles.js  ./static/import-profiles.js
COPY migrate.js          ./static/migrate.js
COPY seed-sqlite.js      ./static/seed-sqlite.js
COPY server.js           ./static/server.js

# Writable directory for SQLite database
RUN mkdir -p /app/data

ENV STATIC_DIR=/app/static
ENV DB_PATH=/app/data/techconnect.sqlite
ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
