# ---- Build stage ----
# Compiles the project from source inside Docker.
# Used for local `docker build .` or `docker compose up --build`.
# CI uses Dockerfile.ci with a pre-built JAR instead (faster, more reliable).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

# Single combined step: Maven downloads dependencies and compiles in one pass.
# The dependency:go-offline optimisation is omitted here because the Maven
# wrapper itself must download Maven from the internet first, which makes the
# separate caching layer unreliable in restricted Docker networking environments.
RUN chmod +x mvnw && ./mvnw -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /build/target/chatbot.jar app.jar
RUN chown appuser:appuser app.jar
USER appuser

EXPOSE 8080
ENV PORT=8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:${PORT}/api/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
