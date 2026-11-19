# Docker Deployment Guide for Page Search API

This guide covers building, publishing, and deploying the Page Search API using Docker containers.

## Overview

The Page Search API is containerized and published to Docker Hub. This allows for:
- Consistent deployments across all environments
- Easy versioning and rollback
- Simplified dependency management (no shared Apache Tomcat)
- Configuration via environment variables

## Dependencies

**Solr**: This API requires a Solr instance to connect to. Solr configuration and deployment is managed in a separate repository:
- [arquivo-solr-tools](https://github.com/arquivo/arquivo-solr-tools) - Contains Solr image definitions, configuration, and deployment scripts for both page and image search indexes.

## Prerequisites

- Docker and Docker Compose installed
- For local builds: Maven 3.6.3+ and JDK 25
- Docker Hub account with push access (for publishing images)
- Access to a Solr instance (dev, staging, or production)

## Automatic CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/docker-build.yml`) automatically:

1. **Builds** the Maven project on push to the `main` branch
2. **Builds** Docker image using the compiled WAR file
3. **Pushes** to `arquivo/page-search-api` on Docker Hub with tags:
   - `latest` (for main)
   - Branch name (e.g., `main`, or the branch of a manual run)
   - Semantic version (for git tags like `v1.0.0`)
   - Short commit SHA

### Docker Hub Secrets Setup

Add these secrets to your GitHub repository:
- `DOCKERHUB_USERNAME`: Your Docker Hub username
- `DOCKERHUB_TOKEN`: Your Docker Hub personal access token

## Local Development & Validation

### Build Locally

The Dockerfile uses a multi-stage build: Maven compiles the WAR in the first stage, then it's
run standalone on its embedded (provided-scope) Tomcat in a plain JRE image. The build context
must be the repository root, since `page-search-api` depends on the sibling `utils` reactor
module.

```bash
cd page-search
docker build -f page-search-api/Dockerfile -t arquivo/page-search-api:local .
```

### Run with Docker Compose

The API requires a Solr instance to connect to. Configure the `SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK` environment variable to point to your Solr instance. The name looks verbose, but it's deliberate: it's Spring's relaxed-binding form of the `searchpages.textsearch.service.bean.solr.link` property, so it must be spelled exactly like this to actually take effect — a shorter alias like `SOLR_URL` would silently be ignored.

#### Option 1: Export environment variable (quick testing)

```bash
export SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK=http://solr-dev-host:8983/solr
docker-compose up -d
```

Or as a one-liner:
```bash
SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK=http://solr-dev-host:8983/solr docker-compose up -d
```

#### Option 2: Use `docker-compose.override.yml` (recommended for local development)

Create `docker-compose.override.yml` in the repo root:

```yaml
services:
  page-search-api:
    environment:
      SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK: http://solr-dev-host:8983/solr
      # Local dev only: the plain-Xmx form is fine here since this compose file sets no
      # mem_limit — see "Production Memory Configuration" below for the recommended
      # G1GC/percentage-based flags a real deployment should use instead.
      JAVA_OPTS: -Xmx4g -Xms2g
```

Add to `.gitignore`:
```
docker-compose.override.yml
```

Then simply run:
```bash
docker-compose up -d
```

Docker Compose automatically merges this file with `docker-compose.yml`.

#### Option 3: Use environment file

Create `.env.local`:
```
SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK=http://solr-dev-host:8983/solr
JAVA_OPTS=-Xmx4g -Xms2g
```

Add to `.gitignore`:
```
.env.local
```

Then run:
```bash
docker-compose up -d
```

### Managing Services

```bash
# View logs
docker-compose logs -f page-search-api

# Stop services
docker-compose down

# Restart services
docker-compose restart page-search-api
```

API will be available at `http://localhost:8080`

## Production Memory Configuration

The image ships with default JVM flags (see `JAVA_OPTS` in the `page-search-api/Dockerfile`) tuned for running in a memory-constrained container:

- `-XX:+UseG1GC` — always G1, for predictable pause times on a request/response API (the JVM falls back to Serial GC on containers with fewer than 2 CPUs or less than 1792 MB of memory).
- `-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0` — size the heap as a percentage of the container's memory limit instead of a fixed `-Xmx`/`-Xms`, so the same image behaves correctly across environments with different memory limits.
- `-XX:+ExitOnOutOfMemoryError` — exit on OOM instead of limping along in a broken state, so the orchestrator restarts the container.

**These percentage-based flags only work correctly if the container has an explicit memory limit** — without one, the JVM falls back to sizing off the host's total memory, which is not what you want in production. Always set a memory limit when running the container:

```bash
docker run -p 8080:8080 --memory=4g arquivo/page-search-api
```

In Kubernetes, set the equivalent `resources.limits.memory` (and match `requests.memory` to it for predictable scheduling/sizing). In an Ansible-managed `docker-compose.yml`, use the top-level `mem_limit` key (see the Preprod/Production examples below).

**Recommended memory limit: 4 GiB.** Load testing (`ab` against `/textsearch?maxItems=50`, hitting the real dev Solr backend at `p44.arquivo.pt`, at increasing concurrency) compared 1 GiB, 2 GiB, and 4 GiB limits. As with image-search-api, raw "% memory used at peak" isn't the most meaningful signal on its own — G1's young-generation sizing scales with whatever heap ceiling it's given, so a bigger limit legitimately shows higher utilization even when the necessary working set hasn't changed. The signals that matter are distress indicators: Full GC events, "to-space exhausted" evacuation failures (heap pressure during a collection), and actual OOM-kills.

| Memory limit | Concurrency | Peak memory | Full GC | To-space exhausted | Result |
|---|---|---|---|---|---|
| 1 GiB | up to 250 | ~99% | 7 | 0 | JVM OOM-killed itself (`-XX:+ExitOnOutOfMemoryError`, exit code 3) partway through the 250-concurrent run |
| 2 GiB | up to 250 | ~92%+ | 4 | 1 | Also OOM-killed at 250 concurrent, though later than at 1 GiB |
| 4 GiB | up to 250 | ~86% | 14 | 1 | Survived the full run; all GC pauses stayed short (100-200ms), no OOM-kill |

Unlike image-search-api's load test (where 2 GiB was already safe), **page-search-api's `/textsearch` OOM-killed at both 1 GiB and 2 GiB** under the same concurrency. This tracks with `/textsearch` returning a heavier per-request JSON payload than image search's (richer per-page metadata, dedup logic across results), so more concurrent in-flight requests translate to more live heap held at once while waiting on the network round trip to Solr. 4 GiB is therefore the recommended floor here, not just a headroom choice — 2 GiB measurably crashed under realistic load.

This number is based on synthetic load against a shared dev Solr instance, not real arquivo.pt production traffic — revisit it once real peak-concurrency numbers are available.

To override the defaults (e.g. a different heap percentage, or disabling G1), set `JAVA_OPTS` at runtime — it replaces the Dockerfile's default entirely:

```bash
docker run -p 8080:8080 --memory=4g \
  -e JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError" \
  arquivo/page-search-api
```

### Concurrency limit: Tomcat's thread pool

Independently of container memory, Spring Boot's embedded Tomcat caps concurrent request processing with its own defaults, unmodified in this project: `server.tomcat.threads.max=200` (max worker threads) and `server.tomcat.accept-count=100` (extra connections queued once all 200 threads are busy, beyond which new connections are refused). This showed up directly in the load test: at 250 concurrent requests even on the surviving 4 GiB run, throughput collapsed to ~3 requests/sec with ~83s average latency — not from GC pressure (pauses stayed at 100-200ms) but from requests queuing behind the 200-thread/100-backlog ceiling while each request waits ~1-2s on the Solr round trip. Raising the memory limit alone doesn't fix this; it only gives the JVM more room to run at the existing 200-thread ceiling.

To raise the ceiling, pass the equivalent Spring Boot properties as JVM system properties in `JAVA_OPTS` (or as `SERVER_TOMCAT_THREADS_MAX`/`SERVER_TOMCAT_ACCEPT_COUNT` environment variables, via Spring's relaxed env binding):

```bash
docker run -p 8080:8080 --memory=4g \
  -e JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Dserver.tomcat.threads.max=400 -Dserver.tomcat.accept-count=200" \
  arquivo/page-search-api
```

Raising the thread cap increases the number of requests that can be in flight at once, and each one holds its own thread stack and request/response buffers — so it also raises the memory the JVM can actually use under load. Re-run load testing at the new thread count before increasing it in production, and scale the memory limit up alongside it rather than in isolation.

### Known follow-up: unconfigured Solr client

`SolrSearchService` builds its `HttpSolrClient` (SolrJ) with no explicit connection-pool sizing or connect/socket timeouts — it's possible this becomes a concurrency bottleneck before Tomcat's thread pool does, under different traffic shapes than what was load-tested here. Not addressed in this change; worth profiling separately if concurrency needs to be pushed materially above what's documented above.

## Environment Deployment

The `docker-compose.yml` is a generic template for local validation. For environment-specific deployments:

1. Manage environment-specific `docker-compose.yml` files in your Ansible repository
2. Configure `SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK` to point to your environment's Solr instance
3. Override environment variables as needed for each environment
4. Use the same Docker image across all environments

**Note:** Solr is managed separately in the [arquivo-solr-tools](https://github.com/arquivo/arquivo-solr-tools) repository and should be deployed independently.

### Example: Configuring for Preprod

Create a `docker-compose.yml` in your Ansible repository:

```yaml
version: '3.8'

services:
  page-search-api:
    image: arquivo/page-search-api:v1.2.3
    mem_limit: "4g"
    environment:
      SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK: http://solr-preprod:8983/solr
      JAVA_OPTS: -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError
    # ... rest of config
```

### Example: Configuring for Production

```yaml
version: '3.8'

services:
  page-search-api:
    image: arquivo/page-search-api:v1.2.3
    mem_limit: "4g"
    environment:
      SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK: http://solr-prod:8983/solr
      JAVA_OPTS: -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError
    # ... rest of config
```

`mem_limit` is what makes the percentage-based `JAVA_OPTS` flags size the heap correctly — see "Production Memory Configuration" above for why 4 GiB is the recommended floor.

## Container Configuration

### Health Checks

Health checking is configured in `docker-compose.yml`, not baked into the Docker image. The
default check is a shallow TCP liveness probe against port 8080 (via bash's `/dev/tcp`, no extra
tools required):

```yaml
healthcheck:
  test: ["CMD-SHELL", "cat < /dev/null > /dev/tcp/127.0.0.1/8080 || exit 1"]
  interval: 30s
  timeout: 3s
  retries: 3
  start_period: 5s
```

This intentionally only checks that Tomcat is accepting connections — it does not exercise
business logic or the Solr backend. A "deep" check (e.g. hitting `/textsearch`) could return
errors, or simply be slow, whenever Solr itself is overloaded rather than broken, which risks
Docker restarting an otherwise-healthy API container in a cascading fashion. Since the check
lives in `docker-compose.yml` rather than the image, each environment's Ansible-managed compose
file can override it with different semantics if that environment needs something stricter.

The app does separately expose `GET /textsearch/healthcheck`, a deep check that pings Solr and
returns 503 when it's unreachable. It's deliberately not wired into the container `HEALTHCHECK`
above, for the same cascading-restart reason — it exists for external rolling-deploy gates (e.g.
the webapp's aggregate backend healthcheck) to poll instead.

### Environment Variables

All configuration is done via environment variables passed to the container at runtime.

| Variable | Default | Description |
|----------|---------|-------------|
| `SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK` | `http://localhost:8983/solr/searchpages` | Solr server URL. Spring's relaxed-binding form of the `searchpages.textsearch.service.bean.solr.link` property — the var name must match exactly, a plain `SOLR_URL` will not bind to it |
| `SEARCHPAGES_HEALTHCHECK_SOLR_CONNECTIONTIMEOUT_MS` | `2000` | Connection timeout (ms) for the `/textsearch/healthcheck` ping to Solr |
| `SEARCHPAGES_HEALTHCHECK_SOLR_SOCKETTIMEOUT_MS` | `3000` | Socket timeout (ms) for the `/textsearch/healthcheck` ping to Solr |
| `SERVER_PORT` | `8080` | API server port |
| `JAVA_OPTS` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError` | JVM memory and GC options. Heap is sized as a percentage of the container's memory limit, so a `--memory`/`mem_limit` must be set (see "Production Memory Configuration") |

### Port Mapping

| Service | Default Port | Description |
|---------|--------------|-------------|
| API | 8080 | Page Search API (Swagger UI at `/textsearch/api-docs`, OpenAPI spec at `/textsearch/api-docs/v3`) |
| Solr | 8983 | Solr search server |

## Management Commands

### View Logs

```bash
docker-compose logs -f page-search-api
```

### Check Status

```bash
# View running containers (includes health status)
docker-compose ps

# Manual port check
docker-compose exec page-search-api bash -c 'cat < /dev/null > /dev/tcp/127.0.0.1/8080 && echo OPEN || echo CLOSED'
```

### Update Image

```bash
# Pull latest version
docker-compose pull

# Restart with new image
docker-compose up -d --force-recreate
```

### Cleanup

```bash
# Stop services
docker-compose down

# Remove volumes (careful - deletes data)
docker-compose down -v

# Remove all unused Docker images
docker image prune -a
```

## Publishing New Versions

### Automatic (Recommended)

1. Push to the `main` branch or create a git tag:
   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```
2. GitHub Actions automatically builds and pushes to Docker Hub

### Manual

```bash
docker build -f page-search-api/Dockerfile -t arquivo/page-search-api:v1.2.3 .
docker push arquivo/page-search-api:v1.2.3
```

## Troubleshooting

### Container fails to start

```bash
# Check logs
docker-compose logs page-search-api

# Check health status
docker inspect <container_id> | grep -A 20 Health
```

### Connection issues to Solr

```bash
# Verify connectivity from inside the container
docker-compose exec page-search-api curl $SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK/admin/cores
```

### Out of memory errors

`-XX:+ExitOnOutOfMemoryError` (the default `JAVA_OPTS`) means an out-of-memory condition
shows up as the container exiting with code `3`, not a hang — check `docker inspect
<container_id>` for the exit code, and confirm it wasn't Docker's own OOM killer instead
(`docker inspect --format='{{.State.OOMKilled}}' <container_id>`).

Load testing found 1 GiB and 2 GiB memory limits both OOM-kill under realistic concurrency
(see "Production Memory Configuration" above) — raise the limit to at least 4 GiB:

```yaml
mem_limit: "4g"
environment:
  JAVA_OPTS: -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError
```

If 4 GiB still OOMs in your environment, check for higher sustained concurrency than what
was load-tested here, and consider raising `-XX:MaxRAMPercentage` only after confirming the
container's memory limit itself has enough headroom above it.

## Advanced Configuration

### Custom Java Options

Override at runtime — this replaces the Dockerfile's default `JAVA_OPTS` entirely, so
include the base flags too if you still want them:

```bash
export JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Dserver.tomcat.threads.max=400 -Dserver.tomcat.accept-count=200"
docker-compose up -d
```

### Using External Solr Instance

```bash
export SEARCHPAGES_TEXTSEARCH_SERVICE_BEAN_SOLR_LINK=http://external-solr-host:8983/solr
docker-compose up -d
```
