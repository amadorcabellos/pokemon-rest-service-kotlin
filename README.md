# poke-rest-service

A Spring Boot REST API that serves Pokémon data from [PokeAPI](https://pokeapi.co). It stores results in
**PostgreSQL** and caches them in **Redis**. Calls to PokeAPI are protected by **Resilience4j** (retry,
circuit breaker and rate limiter). The API is documented with **OpenAPI 3** and **Swagger UI**.

Built with Java 21, Spring Boot 4.1 and Gradle 9.

---

## Contents

- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Project layout](#project-layout)
- [Prerequisites](#prerequisites)
- [Running the application](#running-the-application)
- [API](#api)
- [API documentation (OpenAPI / Swagger UI)](#api-documentation-openapi--swagger-ui)
- [Resilience](#resilience)
- [Configuration](#configuration)
- [Testing](#testing)
- [Database migrations](#database-migrations)
- [Docker](#docker)
- [Troubleshooting](#troubleshooting)
- [Next steps](#next-steps)
- [AI-assisted development](#ai-assisted-development)

---

## Architecture

The service reads data in this order: Redis cache, then PostgreSQL, then PokeAPI.

```
            GET /api/v1/pokemon/{nameOrId}
                         │
                         ▼
               ┌───────────────────┐
               │ PokemonController │  input validation, RFC 9457 errors
               └─────────┬─────────┘
                         ▼
               ┌───────────────────┐   hit   ┌─────────┐
               │  PokemonService   │ ◄────── │  Redis  │  @Cacheable("pokemon"), TTL 1h
               └─────────┬─────────┘         └─────────┘
                    miss │
                         ▼
               ┌───────────────────┐   hit   ┌────────────┐
               │ PokemonRepository │ ◄────── │ PostgreSQL │  Flyway-managed schema
               └─────────┬─────────┘         └────────────┘
                    miss │
                         ▼
               ┌───────────────────┐
               │   PokeApiClient   │  Resilience4j: Retry → CircuitBreaker → RateLimiter
               └─────────┬─────────┘
                         │ HTTP (RestClient + JDK HttpClient)
                         ▼
                    ┌──────────┐
                    │ PokeAPI  │
                    └──────────┘
```

1. A request first checks the Redis `pokemon` cache. The cache key is the input, trimmed and lowercased.
2. On a cache miss, the service looks in PostgreSQL. Numeric input is matched against the PokeAPI id.
   Any other input is matched against the name, ignoring case.
3. If the Pokémon isn't stored yet, the service fetches it from PokeAPI, saves it, and returns it. The
   response is then cached. The PokeAPI call goes through the [resilience](#resilience) layer.

Only step 3 calls PokeAPI. Cached and stored Pokémon are still served when PokeAPI is down, when the circuit
breaker is open, or when the rate limit is used up.

Error mapping:

| Situation                                          | HTTP status               |
|----------------------------------------------------|---------------------------|
| Invalid `nameOrId` format                          | `400 Bad Request`         |
| PokeAPI returns 404                                | `404 Not Found`           |
| Outgoing PokeAPI rate limit used up                | `429 Too Many Requests`   |
| PokeAPI unreachable / 5xx / timeout, after retries | `502 Bad Gateway`         |
| Circuit breaker open (PokeAPI calls paused)        | `503 Service Unavailable` |

All error responses are [Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`).

## Tech stack

| Concern            | Choice                                                         |
|--------------------|----------------------------------------------------------------|
| Language / runtime | Java 21 (virtual threads enabled)                              |
| Framework          | Spring Boot 4.1 (Web MVC, Data JPA, Data Redis, Cache, Validation, Actuator) |
| HTTP client        | Spring `RestClient`                                            |
| Resilience         | Resilience4j 2.4 (`resilience4j-spring-boot4`): retry, circuit breaker, rate limiter |
| API docs           | springdoc-openapi 3.1 (OpenAPI 3 + Swagger UI)                 |
| Database           | PostgreSQL 17 + Flyway                                         |
| Cache              | Redis 7 via Spring Cache abstraction                           |
| Build              | Gradle 9 (wrapper included) with JVM Test Suites               |
| Unit tests         | JUnit 5, Mockito, AssertJ, `@WebMvcTest`, `MockRestServiceServer` |
| Integration tests  | Testcontainers (PostgreSQL, Redis, WireMock)                   |
| Packaging          | Multi-stage Dockerfile (layered jar) + Docker Compose          |

## Project layout

```
.
├── build.gradle                     # dependencies, test suites, bootTestRun wiring
├── compose.yaml                     # postgres + redis + app
├── Dockerfile                       # multi-stage, layered, non-root image
└── src
    ├── main
    │   ├── java/com/ballastlane/poke
    │   │   ├── PokeRestServiceApplication.java
    │   │   ├── client/              # PokeApiClient + PokeAPI DTOs
    │   │   ├── config/              # RestClient, Redis cache, OpenAPI, @ConfigurationProperties
    │   │   ├── domain/              # JPA entities
    │   │   ├── exception/           # domain exceptions
    │   │   ├── mapper/              # PokeAPI DTO ⇄ entity ⇄ response
    │   │   ├── repository/          # Spring Data JPA repositories
    │   │   ├── service/             # business logic + caching
    │   │   └── web/                 # controllers (OpenAPI-annotated), DTOs, exception handler
    │   └── resources
    │       ├── application.yml
    │       └── db/migration/        # Flyway scripts
    ├── test                         # unit tests (no Docker needed)
    │   ├── java/...                 # Mockito / WebMvcTest / MockRestServiceServer
    │   └── resources/pokeapi/       # PokeAPI JSON fixtures
    └── integrationTest              # integration tests (Docker required)
        ├── java/com/ballastlane/poke
        │   ├── TestPokeRestServiceApplication.java   # run app with Testcontainers
        │   ├── support/             # shared containers + AbstractApiIntegrationTest base class
        │   ├── repository/          # @DataJpaTest against real PostgreSQL
        │   ├── resilience/          # retry, circuit breaker and rate limiter tests
        │   └── web/                 # full-stack API and OpenAPI tests
        └── resources
            ├── application-integration.yml  # fast resilience settings for tests
            └── wiremock/            # PokeAPI stubs (mappings + __files)
```

## Prerequisites

- **JDK 21.** Gradle toolchains pick it up automatically.
- **Docker.** Needed for Docker Compose and for the Testcontainers integration tests. Docker Desktop,
  OrbStack, Colima and Rancher Desktop all work.

You don't need to install Gradle. Use the included `./gradlew` wrapper.

## Running the application

Pick one of the following options.

### Option 1: Everything in Docker

```bash
docker compose up -d --build
curl http://localhost:8080/api/v1/pokemon/pikachu
open http://localhost:8080/swagger-ui.html
```

To stop the stack, run `docker compose down`. Add `-v` to also delete the PostgreSQL volume.

### Option 2: Infrastructure in Docker, app from Gradle or your IDE

```bash
docker compose up -d postgres redis
./gradlew bootRun
```

The defaults in `application.yml` point to `localhost:5432` (user, password and database are all `poke`)
and `localhost:6379`, which match `compose.yaml`.

### Option 3: Testcontainers dev mode (no compose needed)

```bash
./gradlew bootTestRun
```

This starts `TestPokeRestServiceApplication`. It creates fresh PostgreSQL and Redis containers and wires
them in automatically through `@ServiceConnection`. The data is discarded when the app stops. You can
also run that class from your IDE.

## API

Base path: `/api/v1/pokemon`

### Get a Pokémon by name or id

```http
GET /api/v1/pokemon/{nameOrId}
```

`nameOrId` must match `^[a-zA-Z0-9-]{1,50}$`, for example `pikachu`, `25` or `mr-mime`.

```bash
curl -s http://localhost:8080/api/v1/pokemon/pikachu
```

```json
{
  "id": 25,
  "name": "pikachu",
  "height": 4,
  "weight": 60,
  "baseExperience": 112,
  "spriteUrl": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png",
  "types": ["electric"]
}
```

`id` is the PokeAPI id, not the internal database key.

Not found:

```json
{
  "title": "Pokemon not found",
  "status": 404,
  "detail": "Pokemon 'missingno' was not found",
  "instance": "/api/v1/pokemon/missingno"
}
```

### List stored Pokémon

```http
GET /api/v1/pokemon?page=0&size=20&sort=name,asc
```

This lists only the Pokémon already saved in PostgreSQL, that is, ones requested at least once. By
default it returns 20 items per page, sorted by `pokeApiId` in ascending order.

```json
{
  "content": [ { "id": 1, "name": "bulbasaur", "...": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### Operational endpoints

| Endpoint                         | Purpose                                             |
|----------------------------------|-----------------------------------------------------|
| `/actuator/health`               | Aggregated health (includes DB and Redis)           |
| `/actuator/health/liveness`      | Kubernetes liveness probe                           |
| `/actuator/health/readiness`     | Kubernetes readiness probe                          |
| `/actuator/info`                 | Build/app info                                      |
| `/actuator/circuitbreakers`      | Circuit breaker state (`CLOSED`, `OPEN`, `HALF_OPEN`) |
| `/actuator/circuitbreakerevents` | Recent circuit breaker events (failures, state changes) |
| `/actuator/ratelimiters`         | Configured rate limiters                            |
| `/actuator/retries`              | Configured retries                                  |

Resilience4j also publishes Micrometer metrics, such as `resilience4j.circuitbreaker.calls`,
`resilience4j.retry.calls` and `resilience4j.ratelimiter.available.permissions`. A metrics backend such
as Prometheus can pick these up.

## API documentation (OpenAPI / Swagger UI)

[springdoc-openapi](https://springdoc.org) generates the API documentation from the controllers at runtime.

| URL                                            | Content                         |
|------------------------------------------------|---------------------------------|
| http://localhost:8080/swagger-ui.html          | Interactive Swagger UI          |
| http://localhost:8080/v3/api-docs              | OpenAPI 3 document (JSON)       |
| http://localhost:8080/v3/api-docs.yaml         | OpenAPI 3 document (YAML)       |

Where the documentation comes from:

- `OpenApiConfig` sets the global metadata: title, version, description, and links to PokeAPI.
- `PokemonController` has `@Tag`, `@Operation`, `@Parameter` and `@ApiResponse` annotations. Every error
  status (400/404/429/502/503) is documented with the `ProblemDetail` schema. `@ParameterObject` shows
  `page`, `size` and `sort` as separate query parameters.
- `springdoc.paths-to-match: /api/**` limits the document to business endpoints, so actuator endpoints
  are left out.

To export a static copy of the spec, for example for client generation, run
`curl -s localhost:8080/v3/api-docs.yaml > openapi.yaml`.

In production you may want to turn the UI off with `SPRINGDOC_SWAGGER_UI_ENABLED=false` and
`SPRINGDOC_API_DOCS_ENABLED=false`, or put it behind authentication.

## Resilience

`PokeApiClient#fetchPokemon` is the only method that calls PokeAPI. It is annotated with Resilience4j's
`@Retry`, `@CircuitBreaker` and `@RateLimiter`. All three use the instance name `pokeapi`, configured under
`resilience4j.*` in `application.yml`. The decorators are applied in this order:

```
Retry ( CircuitBreaker ( RateLimiter ( HTTP call ) ) )
```

So each retry attempt is recorded by the circuit breaker and uses one rate-limiter permit.

### Retry

| Setting           | Value                                                   |
|-------------------|---------------------------------------------------------|
| Attempts          | 3 in total (1 call + 2 retries)                         |
| Back-off          | Exponential: 500 ms, then 1 s                           |
| Retried on        | `PokeApiException`: 5xx, I/O errors, timeouts           |
| Never retried     | `PokemonNotFoundException` (404), an open circuit, rate-limit rejections |

### Circuit breaker

| Setting                     | Value                                              |
|-----------------------------|----------------------------------------------------|
| Window                      | Last 20 calls (count-based), at least 10 recorded calls |
| Opens when                  | ≥ 50% failures, or ≥ 80% of calls slower than 5 s  |
| Open duration               | 30 s, then moves to half-open on its own           |
| Half-open                   | 3 trial calls decide whether to close or reopen    |
| Counted as failure          | `PokeApiException`                                 |
| Ignored                     | `PokemonNotFoundException`, so a 404 is a valid answer, not a failure |

While the circuit is open, PokeAPI isn't called at all. Requests for Pokémon that aren't stored locally
get a `503` straight away. Cached and stored Pokémon are still served normally.

### Rate limiter

This is a client-side limit on how often this service calls PokeAPI, in line with PokeAPI's
[fair use policy](https://pokeapi.co/docs/v2#fairuse). It does not limit incoming traffic to this API.

| Setting           | Value                                                  |
|-------------------|--------------------------------------------------------|
| Limit             | 20 calls per second (`POKEAPI_RATE_LIMIT`)             |
| Waiting           | Up to 500 ms for a permit, then `429 Too Many Requests` |

### Tuning

You can override any value with standard Spring property binding. For example:

```bash
RESILIENCE4J_RETRY_INSTANCES_POKEAPI_MAXATTEMPTS=5
RESILIENCE4J_CIRCUITBREAKER_INSTANCES_POKEAPI_WAITDURATIONINOPENSTATE=60s
POKEAPI_RATE_LIMIT=50
```

Related change: `PokemonService#getPokemon` is intentionally **not** `@Transactional`, so a slow or
retrying PokeAPI call doesn't hold a database connection. Each repository call runs in its own short
transaction.

## Configuration

All settings live in `src/main/resources/application.yml`. You can override each one with an environment
variable.

| Env variable        | Property                     | Default                                  |
|---------------------|------------------------------|------------------------------------------|
| `DB_URL`            | `spring.datasource.url`      | `jdbc:postgresql://localhost:5432/poke`  |
| `DB_USERNAME`       | `spring.datasource.username` | `poke`                                   |
| `DB_PASSWORD`       | `spring.datasource.password` | `poke`                                   |
| `REDIS_HOST`        | `spring.data.redis.host`     | `localhost`                              |
| `REDIS_PORT`        | `spring.data.redis.port`     | `6379`                                   |
| `POKEAPI_BASE_URL`  | `pokeapi.base-url`           | `https://pokeapi.co/api/v2`              |
| `POKEMON_CACHE_TTL` | `app.cache.pokemon-ttl`      | `1h` (any `Duration`, e.g. `30m`, `PT2H`) |
| `POKEAPI_RATE_LIMIT`| `resilience4j.ratelimiter.instances.pokeapi.limit-for-period` | `20` (calls per second) |

You can also set `pokeapi.connect-timeout` (default `5s`) and `pokeapi.read-timeout` (default `10s`).
For retry, circuit breaker and rate limiter settings, see [Resilience](#resilience). For Swagger UI and
OpenAPI settings, see [API documentation](#api-documentation-openapi--swagger-ui).
Spring's relaxed binding also accepts env vars such as `POKEAPI_READ_TIMEOUT=3s`.

## Testing

Unit tests and integration tests are separate
[Gradle JVM test suites](https://docs.gradle.org/current/userguide/jvm_test_suite_plugin.html):

| Command                       | Runs                         | Docker? |
|-------------------------------|------------------------------|---------|
| `./gradlew test`              | Unit tests (`src/test`)      | No      |
| `./gradlew integrationTest`   | Integration tests (`src/integrationTest`) | Yes |
| `./gradlew check` / `build`   | Both                         | Yes     |

Reports are written to `build/reports/tests/test/index.html` and
`build/reports/tests/integrationTest/index.html`.

### Unit tests (Mockito)

| Test                   | What it covers                                                                       |
|------------------------|--------------------------------------------------------------------------------------|
| `PokemonServiceTest`   | Read-through logic with mocked `PokeApiClient` and repository (`@Mock`, `@Spy`, `@InjectMocks`, BDD `given`/`verify`) |
| `PokemonControllerTest`| `@WebMvcTest` slice with `@MockitoBean` service: JSON shape, 400/404/429/502/503 mapping, paging |
| `PokeApiClientTest`    | `RestClient` against `MockRestServiceServer`: deserialization, 404 → `PokemonNotFoundException`, 5xx → `PokeApiException` |
| `PokemonMapperTest`    | DTO/entity/response mapping, type ordering, null-safety                              |

### Integration tests (Testcontainers)

Container setup lives in `src/integrationTest/java/.../support`:

- `ContainersConfiguration` defines the `postgres:17-alpine` and `redis:7-alpine` containers, registered
  with `@ServiceConnection`. Spring Boot reads their connection details directly, so you don't need
  manual properties.
- `WireMockContainer` runs `wiremock/wiremock`, which stands in for PokeAPI. Its stubs are loaded from
  `src/integrationTest/resources/wiremock`. It also has helpers to count and reset recorded requests.
- `IntegrationTestConfiguration` combines the above and uses a `DynamicPropertyRegistrar` to point
  `pokeapi.base-url` at WireMock.
- `AbstractApiIntegrationTest` is the base class for full-stack tests. It enables MockMvc and the
  `integration` profile. Before each test it clears the database, the Redis cache, WireMock's request
  journal and the circuit breaker.

The containers are static, JVM-wide singletons, so they start **once per test run**. Every Spring test
context reuses them, including contexts with different properties.

`application-integration.yml`, activated by the `integration` profile, shrinks the resilience settings so
the tests run fast and give the same result every time. Retries wait 10 ms, and the circuit opens after 4
recorded calls.

| Test                               | What it covers                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `PokemonApiIntegrationTest`        | HTTP → service → Redis/PostgreSQL → WireMock. Checks that the first call hits PokeAPI, the second is served from cache, a DB fallback works after the cache is cleared, 404/502 handling, listing, and actuator health |
| `PokeApiResilienceIntegrationTest` | A 5xx is retried 3 times. A 404 is neither retried nor counted by the circuit breaker. Repeated failures open the circuit, and later calls get `503` without reaching PokeAPI (checked through `/actuator/circuitbreakers`). Cached data is still served while the circuit is open |
| `PokeApiRateLimiterIntegrationTest`| Uses its own context with a quota of 2 calls that doesn't refresh. The third upstream call gets `429` and never reaches WireMock, while cached Pokémon are still served |
| `OpenApiIntegrationTest`           | `/v3/api-docs` has the expected metadata, operations, documented error responses and paging parameters, and excludes actuator. Swagger UI is served |
| `PokemonRepositoryIntegrationTest` | `@DataJpaTest` against real PostgreSQL with Flyway: queries, type ordering, unique constraints |

To add a PokeAPI stub, put a mapping in `wiremock/mappings/` and, if needed, a body file in
`wiremock/__files/`. The catch-all `not-found.json` mapping has lower priority, so any path without its
own stub returns 404.

## Database migrations

Flyway manages the schema. Hibernate runs with `ddl-auto: validate`, so it checks the entities against the
schema but never changes it.

- `V1__create_pokemon_tables.sql` creates the `pokemon` table and the `pokemon_type` table (ordered by slot).

To change the schema, add a new file named `src/main/resources/db/migration/V{n}__description.sql`. Don't
edit migrations that have already been applied.

## Docker

The `Dockerfile` has two stages:

1. **Build** (`eclipse-temurin:21-jdk`): resolves dependencies in a cached layer, builds the Boot jar, and
   extracts it into layers with `-Djarmode=tools`.
2. **Runtime** (`eclipse-temurin:21-jre`): copies the layers from least to most frequently changing, runs
   as a non-root `spring` user, and sets `-XX:MaxRAMPercentage=75`.

```bash
docker build -t poke-rest-service .
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/poke \
  -e REDIS_HOST=host.docker.internal \
  poke-rest-service
```

`compose.yaml` runs PostgreSQL and Redis with health checks. The app waits until both are healthy before it
starts.

## Troubleshooting

- **`Could not find a valid Docker environment` while running integration tests.** Start Docker. With
  Colima or Rancher Desktop you may also need to set `DOCKER_HOST` and
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- **Port 5432, 6379 or 8080 already in use.** Stop the local service that uses it, or change the host-side
  port in `compose.yaml`.
- **Stale cached data.** Run `docker compose exec redis redis-cli FLUSHALL`, or lower `POKEMON_CACHE_TTL`.
- **Getting `503 Service Unavailable` for every new Pokémon.** The circuit breaker is open after repeated
  PokeAPI failures. Check `/actuator/circuitbreakers` and `/actuator/circuitbreakerevents`. It moves to
  half-open on its own after 30 s.
- **Getting `429 Too Many Requests`.** The outgoing PokeAPI quota (`POKEAPI_RATE_LIMIT` per second) is used
  up. Raise it carefully and keep PokeAPI's fair use policy in mind.

## Next steps

This is a skeleton. Some natural extensions:

- A fallback that serves stale data when PokeAPI is unavailable (for example, a longer-lived "stale" cache)
- Retry-After headers on `429`/`503` responses, based on the rate limiter and circuit breaker state
- Handling of concurrent first-time fetches of the same Pokémon (upsert on the unique constraint)
- CI pipeline (GitHub Actions) that runs `./gradlew build`
- Observability: Micrometer metrics, tracing, structured logging

## AI-assisted development

This project was generated and extended with [Claude Code](https://claude.com/claude-code)
(Claude Opus 5.5) in October 2026, using the prompts below (lightly edited). They are kept for
transparency. Running them again won't produce the same code, so the committed code is the source of truth.

**1. Initial skeleton**

```text
Create a Spring Boot skeleton project using Java 21 and Gradle for a REST API that integrates with
PokeAPI. Include PostgreSQL for data storage, Redis for caching, unit tests with Mockito, and
integration tests using Testcontainers. Configure Docker for local execution and generate a
comprehensive README file.
```

**2. API documentation and resilience**

```text
Integrate OpenAPI/Swagger UI using springdoc-openapi and add resilience mechanisms (retry, circuit
breaker, and rate limiting) for external PokeAPI calls. Update the README.md file with the new
features and setup details.
```

All generated code was reviewed. The full test suite (unit and Testcontainers integration tests)
passes, and the app was smoke-tested against the real PokeAPI using Docker Compose.
