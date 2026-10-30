# Stage 1: Build stage
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy POM and dependencies first to leverage Docker layer caching
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as dedicated unprivileged user
RUN addgroup -g 10001 -S spring && \
    adduser -u 10001 -S spring -G spring

# Copy compiled jar
COPY --from=build --chown=spring:spring /app/target/gps-distance-engine-0.0.1-SNAPSHOT.jar app.jar

# Read-only binary for non-root user
USER spring:spring

# JVM flags tuned for containers
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=5s --start-period=15s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

