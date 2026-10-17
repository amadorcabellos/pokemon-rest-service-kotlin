# pokemon-rest-service

A Spring Boot REST API that serves Pokémon data from [PokeAPI](https://pokeapi.co). It stores results in
**PostgreSQL** and caches them in **Redis**. You can also browse the full PokeAPI catalogue page by page,
with each entry's sprite, category, mass and skills. Calls to PokeAPI are protected by **Resilience4j** (retry,
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

The API has two read paths: looking up a single Pokémon, and browsing the catalogue.

### Single Pokémon lookup

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

### Catalogue browsing

```
          GET /api/v1/pokemon?page&size
                         │
                         ▼
             ┌───────────────────────┐   hit   ┌─────────┐
             │ PokemonCatalogService │ ◄────── │  Redis  │  "pokemon-page", key page:size
             └───────────┬───────────┘         └─────────┘
                    miss │ 1 catalogue call: /pokemon?offset&limit (count + names/ids)
                         ▼
             ┌───────────────────────┐   hit   ┌─────────┐
             │ PokemonSummaryLoader  │ ◄────── │  Redis  │  "pokemon-summary", key id
             │ (in parallel, per id) │         └─────────┘
             └───────────┬───────────┘
                    miss │ 1 detail call per uncached Pokémon: /pokemon/{id}
                         ▼
                  PokeApiClient → PokeAPI      (same resilience layer)
```

1. A page request first checks the `pokemon-page` cache, keyed `page:size`.
2. On a miss, the service fetches that slice of the PokeAPI catalogue, which gives the total count and the
   ids on the page.
3. Each entry is loaded from the `pokemon-summary` cache, keyed by id. Only uncached Pokémon are fetched
   from PokeAPI, in parallel on virtual threads. Because entries are cached separately, pages of different
   sizes reuse them.
4. If any entry fails, the whole page fails with the usual error (502, 503 or 429) and isn't cached.
5. Catalogue entries are only cached in Redis. They are not stored in PostgreSQL.

Error mapping (both paths):

| Situation                                          | HTTP status               |
|----------------------------------------------------|---------------------------|
| Invalid `nameOrId`, `page` or `size`               | `400 Bad Request`         |
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
    │   │   ├── service/             # lookup + catalogue services, caching
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
        │   ├── catalog/             # catalogue browsing and caching tests
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

### Browse Pokémon

```http
GET /api/v1/pokemon?page=0&size=20
```

Pages through the **full PokeAPI catalogue**, ordered by PokeAPI id.

| Parameter | Default | Rules                                              |
|-----------|---------|----------------------------------------------------|
| `page`    | `0`     | Zero-based. Must be 0 or greater.                  |
| `size`    | `20`    | 1 to 50 (`CATALOG_DEFAULT_PAGE_SIZE`, `CATALOG_MAX_PAGE_SIZE`) |

Values outside these ranges, or non-numeric values, return `400`. A page past the end of the catalogue
returns `200` with an empty `content` list and correct totals.

```bash
curl -s "http://localhost:8080/api/v1/pokemon?page=0&size=2"
```

```json
{
  "content": [
    {
      "id": 1,
      "name": "bulbasaur",
      "spriteUrl": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/1.png",
      "category": ["grass", "poison"],
      "massKg": 6.9,
      "skills": [
        { "name": "overgrow", "hidden": false },
        { "name": "chlorophyll", "hidden": true }
      ]
    },
    { "id": 2, "name": "ivysaur", "...": "..." }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 1351,
  "totalPages": 676
}
```

| Field       | Meaning                                                                 |
|-------------|-------------------------------------------------------------------------|
| `spriteUrl` | Default front sprite. `null` if PokeAPI has none (some alternate forms). |
| `category`  | The Pokémon's types, in slot order.                                     |
| `massKg`    | Weight in kilograms. PokeAPI reports hectograms, so 69 becomes 6.9.     |
| `skills`    | Abilities in slot order. `hidden` marks hidden abilities.               |

`totalElements` is PokeAPI's catalogue count, which includes alternate forms (ids 10001 and up).

> **Breaking change:** this endpoint used to list only the Pokémon stored in PostgreSQL, and accepted a
> `sort` parameter. It now browses the full PokeAPI catalogue, sorted by id only.

#### Caching

| Cache             | Key         | Content                         |
|-------------------|-------------|---------------------------------|
| `pokemon-page`    | `page:size` | A whole page response           |
| `pokemon-summary` | PokeAPI id  | One catalogue entry             |

Both caches share the `POKEMON_CACHE_TTL` expiry (default 1 hour), so the catalogue total refreshes at
least that often. The first request for a page of 20 makes up to 21 PokeAPI calls. Repeating that page makes
none. Concurrent requests for the same uncached page or entry are de-duplicated within one app instance.

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
| Waiting           | Up to 3 s for a permit, then `429 Too Many Requests`   |

The 3-second wait exists for catalogue browsing. An uncached page of 50 needs 51 PokeAPI calls, which is more
than one second's quota. With a short wait, the later calls would fail with `429` partway through the page.
With 3 seconds, the extra calls wait for the next refresh periods instead. If you lower `POKEAPI_RATE_LIMIT`,
consider lowering `CATALOG_MAX_PAGE_SIZE` too.

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
| `CATALOG_DEFAULT_PAGE_SIZE` | `app.catalog.default-page-size` | `20` |
| `CATALOG_MAX_PAGE_SIZE`     | `app.catalog.max-page-size`     | `50` |

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
| `PokemonCatalogServiceTest` | Page building from the catalogue listing, offset and metadata calculation, partial last page, past-the-end, offset overflow, whole page failing with the original exception |
| `PokemonSummaryLoaderTest`  | Fetches by id, maps to a catalogue entry, propagates client errors |
| `PokemonControllerTest`| `@WebMvcTest` slice with `@MockitoBean` services: JSON shape, default and custom paging, 400 for invalid paging (parameterized), 400/404/429/502/503 mapping |
| `PokeApiClientTest`    | `RestClient` against `MockRestServiceServer`: deserialization (including abilities), catalogue listing, 404 → `PokemonNotFoundException`, 5xx → `PokeApiException` |
| `PokemonMapperTest`    | DTO/entity/response mapping, type and ability ordering, hectograms → kilograms, null-safety |

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
  `integration` profile. Before each test it clears the database, all Redis caches, WireMock's request
  journal and the circuit breaker.

The containers are static, JVM-wide singletons, so they start **once per test run**. Every Spring test
context reuses them, including contexts with different properties.

`application-integration.yml`, activated by the `integration` profile, shrinks the resilience settings so
the tests run fast and give the same result every time. Retries wait 10 ms, and the circuit opens after 4
recorded calls.

| Test                               | What it covers                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `PokemonApiIntegrationTest`        | HTTP → service → Redis/PostgreSQL → WireMock. Checks that the first call hits PokeAPI, the second is served from cache, a DB fallback works after the cache is cleared, 404/502 handling, and actuator health |
| `PokemonCatalogIntegrationTest`    | Browsing a 3-Pokémon WireMock catalogue: entries and metadata, partial last page, a repeated page served from Redis with no PokeAPI calls, entries reused across page sizes, a cached page served while the circuit is open, `503` for an uncached page while open, past-the-end, invalid paging |
| `PokeApiResilienceIntegrationTest` | A 5xx is retried 3 times. A 404 is neither retried nor counted by the circuit breaker. Repeated failures open the circuit, and later calls get `503` without reaching PokeAPI (checked through `/actuator/circuitbreakers`). Cached data is still served while the circuit is open |
| `PokeApiRateLimiterIntegrationTest`| Uses its own context with a quota of 2 calls that doesn't refresh. The third upstream call gets `429` and never reaches WireMock, while cached Pokémon are still served |
| `OpenApiIntegrationTest`           | `/v3/api-docs` has the expected metadata, operations, documented error responses and paging parameters, and excludes actuator. Swagger UI is served |
| `PokemonRepositoryIntegrationTest` | `@DataJpaTest` against real PostgreSQL with Flyway: queries, type ordering, unique constraints |

To add a PokeAPI stub, put a mapping in `wiremock/mappings/` and, if needed, a body file in
`wiremock/__files/`. The catch-all `not-found.json` mapping has lower priority, so any path without its
own stub returns 404. The catalogue stubs (`catalogue-offset-*-limit-*.json`) match on the `offset` and
`limit` query parameters and describe a 3-Pokémon catalogue.

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
docker build -t pokemon-rest-service .
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/poke \
  -e REDIS_HOST=host.docker.internal \
  pokemon-rest-service
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

**3. User Story 01: Pokémon enumeration (spec → plan → implementation)**

This feature used a spec-driven workflow with the project's `/spec` command
(`.claude/commands/spec.md`) and the spec template in `_specs/template.md`.

1. **Spec.** Running the command below created the branch `claude/feature/api-pokemon-list` and generated
   [`_specs/api-pokemon-list.md`](_specs/api-pokemon-list.md). That file holds the requirements, edge cases,
   acceptance criteria and open questions. The open questions were then answered in the spec.

   ```text
   /spec User Story 01 Pokemon Enumeration The system should allow users to browse Pokemon via
   paginated results, displaying each entry's sprite, category, mass, and a collection of their
   skills. Also, implement caching for service responses.
   ```

2. **Plan.** An implementation plan was created from the spec and saved to
   [`_plans/api-pokemon-list.md`](_plans/api-pokemon-list.md). It covers the design decisions, caching
   and resilience approach, step-by-step tasks, affected files and risks.

3. **Implementation.** The plan was implemented as written. The result is the
   [Browse Pokémon](#browse-pokémon) endpoint, together with its unit and integration tests.

All generated code was reviewed. The full test suite (unit and Testcontainers integration tests)
passes, and the app was smoke-tested against the real PokeAPI using Docker Compose.
