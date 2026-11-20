# syntax=docker/dockerfile:1
# Build context must be the repository root (page-search-api depends on
# the sibling "utils" reactor module).
#   docker build -f page-search-api/Dockerfile -t arquivo/page-search-api .

# ---- Build stage: compile the WAR with Maven ----
FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /build

COPY pom.xml pom.xml
COPY utils/pom.xml utils/pom.xml
COPY utils/src utils/src
COPY page-search-api/pom.xml page-search-api/pom.xml
COPY page-search-api/src page-search-api/src

RUN mvn -pl page-search-api -am clean package -DskipTests

# ---- Run stage: WAR runs standalone on its embedded (provided-scope) Tomcat ----
FROM eclipse-temurin:25-jre-noble

WORKDIR /app

COPY --from=build /build/page-search-api/target/page-search-api-*.war app.war

ENV SERVER_PORT=8080
# Size the heap off the container's memory limit (not the host's) so behavior
# stays consistent across environments, always use G1 for predictable pause
# times (the JVM falls back to Serial GC on small containers), and fail fast
# on OOM so the container exits and can be restarted by the orchestrator
# instead of limping along. Requires an explicit --memory/resources.limits.memory on
# the container to work correctly (see DOCKER_DEPLOYMENT.md). Override
# JAVA_OPTS entirely when running the image (it replaces this default).
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"
# The Base64 decode intrinsic of JDK builds before 17.0.21.1, 21.0.13.1 and 25.0.5.1 can write past its
# buffer on CPUs with AVX-512 (CVE-2026-114938). The java launcher always reads JDK_JAVA_OPTIONS, so the
# plain Java decoder stays in place even when JAVA_OPTS is overridden.
ENV JDK_JAVA_OPTIONS="-XX:-UseBASE64Intrinsics"

EXPOSE 8080

# No HEALTHCHECK baked in here — health checking is a docker-compose.yml /
# deployment concern, so operators can pick semantics appropriate to their
# environment (see docker-compose.yml and DOCKER_DEPLOYMENT.md).

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.war"]
