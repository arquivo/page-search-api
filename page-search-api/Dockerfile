# syntax=docker/dockerfile:1
# Build context must be the repository root (page-search-api depends on
# the sibling "utils" reactor module and its own vendored maven-local-repository).
#   docker build -f page-search-api/Dockerfile -t arquivo/page-search-api .

# ---- Build stage: compile the WAR with Maven ----
FROM maven:3.8.7-eclipse-temurin-11 AS build

WORKDIR /build

COPY pom.xml pom.xml
COPY utils/pom.xml utils/pom.xml
COPY utils/src utils/src
COPY page-search-api/pom.xml page-search-api/pom.xml
COPY page-search-api/maven-local-repository page-search-api/maven-local-repository
COPY page-search-api/src page-search-api/src

RUN mvn -pl page-search-api -am clean package -DskipTests

# ---- Run stage: WAR runs standalone on its embedded (provided-scope) Tomcat ----
FROM eclipse-temurin:11-jre-jammy

WORKDIR /app

COPY --from=build /build/page-search-api/target/page-search-api-*.war app.war
COPY page-search-api/search-servers.txt .

ENV NUTCHWAX_SEARCH_FILE=/app/
ENV SERVER_PORT=8080
# Size the heap off the container's memory limit (not the host's) so behavior
# stays consistent across environments, always use G1 for predictable pause
# times (the JVM falls back to Serial GC on small containers), and fail fast
# on OOM so the container exits and can be restarted by the orchestrator
# instead of limping along. Requires an explicit --memory/resources.limits.memory on
# the container to work correctly (see DOCKER_DEPLOYMENT.md). Override
# JAVA_OPTS entirely when running the image (it replaces this default).
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# No HEALTHCHECK baked in here — health checking is a docker-compose.yml /
# deployment concern, so operators can pick semantics appropriate to their
# environment (see docker-compose.yml and DOCKER_DEPLOYMENT.md).

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.war"]
