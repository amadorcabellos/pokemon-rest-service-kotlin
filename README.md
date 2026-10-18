# pokemon-rest-service

A Spring Boot REST API that serves Pokémon data from [PokeAPI](https://pokeapi.co). It caches responses in
**Redis** and records looked-up Pokémon in **PostgreSQL**. You can look up a detailed view of one Pokémon
(image, base stats, description and evolution chain), and browse the full PokeAPI catalogue page by page,
with each entry's sprite, category, mass and skills. Admin endpoints synchronize the PokeAPI catalogue into
PostgreSQL, where it sits next to proprietary data (localized names, geographical metadata and tags) that
a sync never overwrites. That proprietary data is shown in the detailed view and can be updated with `PUT` and
`PATCH`, with strict validation and optimistic concurrency (`ETag` / `If-Match`). Users register and sign in
through a small auth API; updates need a `USER` access token and synchronization an `ADMIN` one, while reading
stays public. Calls to PokeAPI are protected by **Resilience4j** (retry, circuit breaker and rate limiter). The
API is documented with **OpenAPI 3** and **Swagger UI**.

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

The API has two read paths, looking up a single Pokémon and browsing the catalogue, plus an admin path
that [synchronizes PokeAPI into PostgreSQL](#data-synchronization) and [updates of local data](#local-data-updates).
[Authentication](#authentication) decides who may call the routes that change data.

### Pokémon details lookup

```
            GET /api/v1/pokemon/{nameOrId}
                         │
                         ▼
               ┌───────────────────┐
               │ PokemonController │  input validation, RFC 9457 errors
               └─────────┬─────────┘
                         ▼
           ┌───────────────────────┐   hit   ┌─────────┐
           │ PokemonDetailsService │ ◄────── │  Redis  │  "pokemon-details", key = trimmed, lowercased input
           └───────────┬───────────┘         └─────────┘
                  miss │
                       ├─► PokeApiClient: /pokemon/{nameOrId}       image, stats, species link
                       ├─► PostgreSQL: insert the basic Pokémon if it isn't stored yet
                       ├─► PokeApiClient: /pokemon-species/{id}     description, evolution chain link
                       ▼
           ┌───────────────────────┐   hit   ┌─────────┐
           │ EvolutionChainLoader  │ ◄────── │  Redis  │  "evolution-chain", key = chain id
           └───────────┬───────────┘         └─────────┘
                  miss │
                       └─► PokeApiClient: /evolution-chain/{id}     (same resilience layer)
```

1. A request first checks the `pokemon-details` cache. The key is the input, trimmed and lowercased.
2. On a miss, the service fetches the Pokémon from PokeAPI. The first time a Pokémon is seen, its basic data
   (name, height, weight, base experience, sprite, types) is stored in PostgreSQL.
3. It then fetches the Pokémon's **species**. The species, not the Pokémon's name, links to the description
   and the evolution chain, so alternate forms like `deoxys-attack` resolve correctly.
4. The evolution chain comes from the `evolution-chain` cache, keyed by chain id, so every member of a family
   shares one cached chain. The "current" marker for the requested Pokémon is added afterwards, so it is
   never stored in the shared chain.
5. If any PokeAPI call fails, the whole request fails with the usual error (502, 503 or 429) and nothing is
   cached.
6. The controller then adds `localData`, the Pokémon's [proprietary data](#local-data-updates), read from
   PostgreSQL on every request. It is never cached, so an update shows straight away even when the rest of the
   view comes from Redis. Its version is sent as the `ETag` header.

An uncached lookup costs up to 3 PokeAPI calls. A cached one costs none, and is still served when PokeAPI is
down, when the circuit breaker is open, or when the rate limit is used up. Apart from `localData`, PostgreSQL
isn't used to answer the lookup, because it doesn't hold descriptions or evolution data.

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

### Data synchronization

```
POST /api/v1/admin/sync/runs ──► SyncRunStore.start ──► sync_run row (RUNNING)   ──► 202 + Location
                                      │                  unique index: one RUNNING run at a time (else 409)
                                      ▼
                          FullSyncRunner (background, applicationTaskExecutor)
                                      │  for each catalogue page (app.sync.batch-size):
                                      │    for each pokemon, one at a time:
                                      │      PokeApiClient.fetchPokemon (retry, circuit breaker, rate limiter)
                                      │      PokemonSyncWriter.upsert   (own transaction, match on PokeAPI id)
                                      │      wait app.sync.delay-between-pokemon
                                      │    save counts and failures to sync_run
                                      ▼
                          sync_run: COMPLETED | COMPLETED_WITH_ERRORS | FAILED

POST /api/v1/admin/sync/pokemon/{nameOrId} ──► fetchPokemon ──► PokemonSyncWriter.upsert ──► 200
```

The local store separates two kinds of data:

- **Upstream data** belongs to PokeAPI: the `pokemon` row (name, height, weight, base experience, sprite,
  species, `last_synced_at`) and its `pokemon_type`, `pokemon_stat` and `pokemon_ability` rows. The sync
  creates and refreshes it.
- **Proprietary data** belongs to this service: `pokemon_localized_name` (one name per locale),
  `pokemon_geo_metadata` (region, area or habitat, with an optional note) and `pokemon_tag` (lowercase, unique
  per Pokémon). Each table points at `pokemon.id`. The `Pokemon` entity deliberately has no mapping to these
  tables, so saving a Pokémon can never cascade into them. Syncs and lookups never change or delete them.

How a sync behaves:

- **Idempotent.** Pokémon are matched on their PokeAPI id. Running a sync again updates rows instead of adding
  new ones, and a renamed Pokémon keeps its row. Rows recorded earlier by a lookup are filled in.
- **Partial failures.** A Pokémon that PokeAPI can't return (for example a 404, or a 5xx after retries) is
  recorded in the run's `failures` and skipped. Its old row is kept, and the run ends as `COMPLETED_WITH_ERRORS`.
- **Back-pressure.** When the circuit breaker or rate limiter rejects a call, the sync waits
  `app.sync.pause-when-rejected` and retries the same call. After `app.sync.max-consecutive-pauses`
  rejections in a row it stops as `FAILED`. Pokémon already synchronized are kept.
- **Fair use of the rate limit.** The sync and normal API traffic share the `pokeapi` rate limiter. The default
  delay of 100 ms between Pokémon keeps the sync to about half of the 20 calls/s quota. A full sync of
  around 1,300 Pokémon takes a few minutes.
- **Restarts.** On startup, a run left `RUNNING` by a previous instance is marked `INTERRUPTED`. This assumes
  a single application instance.
- **Scheduling.** A scheduled full sync can run on `app.sync.schedule.cron`. It is disabled by default
  (`SYNC_SCHEDULE_ENABLED=false`), and is skipped while another sync is running. Nothing syncs on startup.
- **Caches.** A sync only writes PostgreSQL. The public endpoints keep reading from PokeAPI and Redis.

### Local data updates

```
PUT|PATCH /api/v1/pokemon/{nameOrId}   (If-Match optional)
        │
        ├─► LocalDataRequestSizeFilter    body over 64 KB → 413
        ├─► LocalDataRequestParser        strict JSON, every invalid field listed → 400
        ▼
  LocalDataService.update  (one transaction, PostgreSQL only, PokeAPI is never called)
        │  find the Pokémon locally by name or PokeAPI id                     → 404 if not stored
        │  SELECT local_data_version ... FOR NO KEY UPDATE   (row lock: updates of one Pokémon run in turn)
        │  If-Match given and not the current version                        → 412, nothing written
        │  write only what changed: localized names by locale, tags by value, geo entries as a whole
        │  something changed? UPDATE pokemon SET local_data_version + 1, local_data_modified_at
        ▼
  200 + ETag "<version>" + the updated localData
```

- **Version on the `pokemon` row.** `local_data_version` and `local_data_modified_at` (migration V3) version
  the proprietary data. The `Pokemon` entity maps them **read only**: the sync saves whole `Pokemon` entities,
  and an updatable mapping would let it write a stale version back over a newer one. Only
  `PokemonRepository#bumpLocalDataVersion` writes them, so syncs and lookups never change the version.
- **Concurrency.** The row lock serializes updates of the same Pokémon. A sync of that Pokémon waits for the
  lock (milliseconds) and then runs; neither loses the other's writes. Without `If-Match` the last write wins.
- **Idempotent.** An update that changes nothing keeps the version and the modified time.
- **Strict parsing in one place.** The body is parsed by `LocalDataRequestParser` with its own Jackson mapper
  (duplicate keys, trailing content and deep nesting rejected; no type coercion), not bound by Spring MVC. The
  application's shared mapper stays lenient, which the PokeAPI client relies on, and every problem comes back
  as a field-level message.

### Authentication

```
POST /auth/register ──► app_user (USER role, PBKDF2 password hash)
POST /auth/login    ──► check password ──► auth_session (generation 0) ──► access token (JWT, 15 min)
                                                                     └──► refresh token (random, 7 days, hash stored)
PATCH /pokemon/x    ──► Authorization: Bearer <access token>
   (protected)            │ signature + expiry (Nimbus, HS256)
                          │ SessionJwtAuthenticationConverter: session active? token generation current?
                          │   user still there? roles read from the database ──► 401 / 403 / allowed
POST /auth/refresh  ──► refresh token used up ──► session generation + 1 (old access token retired) ──► new pair
                        refresh token already used? ──► session ended (REUSE) ──► 401
POST /auth/logout   ──► session ended (LOGOUT) ──► its access and refresh tokens stop working ──► 204
```

- **Protected by method and path.** Only six routes need a token, plus `GET /api/v1/auth/user`. Everything
  else is public, and on public routes the `Authorization` header isn't even read, so a bad token never breaks
  a public read. Refresh and sign-out are public: the refresh token in the body is their credential.
- **Roles.** Registration gives `USER`, which may update local data. `ADMIN` implies `USER` and may also run
  synchronizations. Only the [initial administrator](#initial-administrator) gets `ADMIN`.
- **Short access tokens, real sign-out.** Access tokens last 15 minutes and refresh tokens 7 days, so a
  client keeps getting new access tokens without signing in again, for up to 7 days between refreshes. Every protected request checks the token's
  session in PostgreSQL (one indexed query), so signing out, refreshing, removing a role or deleting a user
  takes effect on the next request, in every instance.
- **Refresh rotation.** Each refresh token works once. A second use is treated as theft and ends the session.
  A session can be refreshed for at most 30 days (`MAX_SESSION_LIFETIME`); after 7 days without a refresh
  it's over and the user signs in again.
- **Stateless HTTP.** No HTTP session, no cookies, no CSRF token: tokens travel in headers and bodies only.
- **401 before anything else.** Spring Security runs before validation, the size filter and the services, so
  an unauthenticated request never reaches them.

## Tech stack

| Concern            | Choice                                                         |
|--------------------|----------------------------------------------------------------|
| Language / runtime | Java 21 (virtual threads enabled)                              |
| Framework          | Spring Boot 4.1 (Web MVC, Data JPA, Data Redis, Cache, Validation, Actuator) |
| Security           | Spring Security 7 with the OAuth2 resource server (bearer JWTs, Nimbus, HS256), PBKDF2 password hashing |
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
    │   ├── java/com/ballastlane/pokemon
    │   │   ├── PokeRestServiceApplication.java
    │   │   ├── auth/                # registration, sign-in, tokens, sessions, login limiter, initial admin,
    │   │   │                        # and the OpenAPI security scheme name
    │   │   ├── client/              # PokeApiClient + PokeAPI DTOs
    │   │   ├── config/              # RestClient, Redis cache, OpenAPI, @ConfigurationProperties
    │   │   ├── domain/              # JPA entities
    │   │   ├── exception/           # domain exceptions
    │   │   ├── filter/              # servlet filters: request body size limit for local data updates
    │   │   ├── handler/             # errors as Problem Details: GlobalExceptionHandler, security 401/403
    │   │   ├── localdata/           # local (proprietary) data updates: parser, If-Match, service
    │   │   ├── mapper/              # PokeAPI DTO ⇄ entity ⇄ response
    │   │   ├── repository/          # Spring Data JPA repositories
    │   │   ├── service/             # details + catalogue services and cached loaders
    │   │   ├── sync/                # PokeAPI → PostgreSQL synchronization (single and full runs)
    │   │   └── web/                 # controllers (OpenAPI-annotated) and DTOs
    │   └── resources
    │       ├── application.yml
    │       └── db/migration/        # Flyway scripts
    ├── test                         # unit tests (no Docker needed)
    │   ├── java/...                 # Mockito / WebMvcTest / MockRestServiceServer
    │   └── resources/pokeapi/       # PokeAPI JSON fixtures
    └── integrationTest              # integration tests (Docker required)
        ├── java/com/ballastlane/pokemon
        │   ├── TestPokeRestServiceApplication.java   # run app with Testcontainers
        │   ├── support/             # shared containers + AbstractApiIntegrationTest base class
        │   ├── auth/                # registration, sign-in, refresh, sign-out, expiry and lockout tests
        │   ├── catalog/             # catalogue browsing and caching tests
        │   ├── localdata/           # local data updates against PostgreSQL, Redis and syncs
        │   ├── repository/          # @DataJpaTest against real PostgreSQL
        │   ├── resilience/          # retry, circuit breaker and rate limiter tests
        │   ├── sync/                # full and single synchronization tests
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
JWT_SECRET=local-dev-only-jwt-secret-change-me-0123456789 \
ADMIN_USERNAME=admin ADMIN_PASSWORD=local-admin-password \
./gradlew bootRun
```

The defaults in `application.yml` point to `localhost:5432` (user, password and database are all `poke`)
and `localhost:6379`, which match `compose.yaml`. `JWT_SECRET` has no default: the app refuses to start without
one. `compose.yaml` sets local development values for it and for the initial administrator (`admin` /
`local-admin-password`); never reuse them anywhere shared.

### Option 3: Testcontainers dev mode (no compose needed)

```bash
./gradlew bootTestRun
```

This starts `TestPokeRestServiceApplication`. It creates fresh PostgreSQL and Redis containers and wires
them in automatically through `@ServiceConnection`. The data is discarded when the app stops. You can
also run that class from your IDE. Its signing secret comes from `src/integrationTest/resources/application.properties`.

## API

Base paths: `/api/v1/pokemon`, `/api/v1/admin/sync` and `/api/v1/auth`

| Method and path                               | Purpose                                                  | Access |
|-----------------------------------------------|----------------------------------------------------------|--------|
| `GET /api/v1/pokemon/{nameOrId}`              | [Detailed view](#get-pokémon-details-by-name-or-id), with `localData` and an `ETag` | public |
| `GET /api/v1/pokemon?page&size`               | [Browse the catalogue](#browse-pokémon)                  | public |
| `PUT /api/v1/pokemon/{nameOrId}`              | [Replace the local data](#update-local-data) of a stored Pokémon | `USER` |
| `PATCH /api/v1/pokemon/{nameOrId}`            | [Partially update the local data](#update-local-data)   | `USER` |
| `POST /api/v1/admin/sync/runs`                | [Start a full synchronization](#admin-synchronization)  | `ADMIN` |
| `GET /api/v1/admin/sync/runs/latest`, `/runs/{runId}` | [Synchronization status](#admin-synchronization) | `ADMIN` |
| `POST /api/v1/admin/sync/pokemon/{nameOrId}`  | [Synchronize one Pokémon](#admin-synchronization)       | `ADMIN` |
| `POST /api/v1/auth/register`, `/login`, `/refresh`, `/logout` | [Auth](#auth-register-sign-in-refresh-sign-out) | public |
| `GET /api/v1/auth/user`                       | [The signed-in user](#auth-register-sign-in-refresh-sign-out) | signed in |

Protected routes answer `401` without a valid access token (with `WWW-Authenticate: Bearer`) and `403` without
the role. `ADMIN` implies `USER`. Other methods on these paths still get `405`, with or without a token.

### Auth: register, sign in, refresh, sign out

```bash
# Register (always gets the USER role)
curl -i -X POST http://localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{ "username": "ash", "password": "pikachu-i-choose-you" }'

# Sign in: returns an access token and a refresh token
curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{ "username": "ash", "password": "pikachu-i-choose-you" }'
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-18T10:15:00Z",
  "refreshToken": "rmJr1dKMvFDbQ-8GNWsRWdlR0H4dJTutRHFwoGzbxHc",
  "refreshExpiresAt": "2026-10-18T10:15:00Z"
}
```

```bash
# Call a protected route
curl -X PATCH http://localhost:8080/api/v1/pokemon/pikachu -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/merge-patch+json' -d '{ "tags": [ "mascot" ] }'

# Before the tokens expire: a new pair (no access token needed; the old pair stops working)
curl -s -X POST http://localhost:8080/api/v1/auth/refresh -H 'Content-Type: application/json' \
  -d "{ \"refreshToken\": \"$REFRESH\" }"

# Sign out (no access token needed): ends this session only
curl -i -X POST http://localhost:8080/api/v1/auth/logout -H 'Content-Type: application/json' \
  -d "{ \"refreshToken\": \"$REFRESH\" }"

# Who am I?
curl http://localhost:8080/api/v1/auth/user -H "Authorization: Bearer $ACCESS"
```

| Rule | |
|------|---|
| Username | 3 to 50 characters: letters, digits, `.`, `_`, `-`. Stored in lowercase; unique ignoring case |
| Password | 12 to 128 characters, any characters (spaces and Unicode too), not the username. Stored as a PBKDF2 hash |
| Access token | Signed JWT, valid 15 minutes (`ACCESS_TOKEN_TTL`). Send as `Authorization: Bearer ...` |
| Refresh token | Random, valid 7 days (`REFRESH_TOKEN_TTL`), works once. Only its SHA-256 hash is stored |
| Session | One per sign-in; refreshed at most until 30 days after sign-in (`MAX_SESSION_LIFETIME`) |
| Failed sign-ins | 5 within 15 minutes lock that username for 15 minutes (`429` + `Retry-After`), per instance |

| Situation | Response |
|-----------|----------|
| Registered | `201` + `Location: /api/v1/auth/user`, the user without any password |
| Invalid username or password on registration | `400` with every problem in `errors` |
| Username taken | `409` |
| Wrong username or wrong password | `401` "Invalid username or password" (the same for both) |
| Too many failed sign-ins | `429` + `Retry-After` |
| Unknown, expired, used or ended refresh token | `401` "Invalid refresh token"; a used one also ends its session |
| Sign-out | Always `204` for a well-formed request, whether or not the token was valid; `400` without a token |
| Protected route without a valid token | `401` + `WWW-Authenticate: Bearer` (`error="invalid_token"` if one was sent) |
| Protected route without the role | `403` |

#### Initial administrator

`ADMIN` is never given through the API. At startup, if `ADMIN_USERNAME` and `ADMIN_PASSWORD` are both set and
that user doesn't exist, it is created with `USER` and `ADMIN`. An existing user is never changed, and a
password that breaks the policy stops the app from starting.

### Get Pokémon details by name or id

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
  "imageUrl": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/25.png",
  "stats": [
    { "name": "hp", "value": 35 },
    { "name": "attack", "value": 55 },
    { "name": "defense", "value": 40 },
    { "name": "special-attack", "value": 50 },
    { "name": "special-defense", "value": 50 },
    { "name": "speed", "value": 90 }
  ],
  "totalStats": 320,
  "description": "Pikachu that can generate powerful electricity have cheek sacs that are extra soft and super stretchy.",
  "evolution": {
    "id": 172, "name": "pichu", "evolvesVia": null, "current": false,
    "evolvesTo": [
      {
        "id": 25, "name": "pikachu", "evolvesVia": "friendship", "current": true,
        "evolvesTo": [
          { "id": 26, "name": "raichu", "evolvesVia": "use thunder-stone", "current": false, "evolvesTo": [] }
        ]
      }
    ]
  },
  "localData": {
    "localizedNames": [ { "locale": "ja-JP", "name": "ピカチュウ" } ],
    "geoMetadata": [ { "kind": "region", "value": "kanto", "note": "Common in Viridian Forest" } ],
    "tags": [ "mascot" ],
    "modifiedAt": "2026-10-18T10:00:00Z",
    "version": 3
  }
}
```

The response also has an `ETag: "3"` header: the version of `localData`.

| Field         | Meaning                                                                                       |
|---------------|-----------------------------------------------------------------------------------------------|
| `id`          | PokeAPI id, not the internal database key.                                                    |
| `imageUrl`    | Official artwork. Falls back to the default sprite, and is `null` if neither exists.          |
| `stats`       | The six base stats in PokeAPI order. `totalStats` is their sum.                               |
| `description` | English Pokédex text from the most recent game version, cleaned into one line. `null` if none. |
| `evolution`   | The whole evolution chain as a tree, starting at the base form. Branches (for example Eevee) are listed in PokeAPI order. |
| `evolvesVia`  | How a stage evolves from its parent: `level 16`, `use thunder-stone`, `trade holding metal-coat`, `friendship (day)`, and so on. Rarer triggers show the trigger name. Conditions that differ between games are joined with `or`. `null` for the base form. |
| `current`     | `true` on the stage for the requested Pokémon's species.                                      |
| `localData`   | Proprietary data owned by this service (see [Update local data](#update-local-data)), always current. Empty lists, `modifiedAt: null` and `version: 0` until it is first changed. |

> **Breaking change:** this endpoint used to return `height`, `weight`, `baseExperience`, `spriteUrl` and
> `types`. Those fields are no longer in the response. The basic data is still stored in PostgreSQL.

#### Caching

| Cache             | Key                          | Content                                                  |
|-------------------|------------------------------|----------------------------------------------------------|
| `pokemon-details` | Trimmed, lowercased input    | The detailed view without `localData`, which is read from PostgreSQL on every request |
| `evolution-chain` | Evolution chain id           | The chain tree, without the `current` marker             |

Both use the `POKEMON_CACHE_TTL` expiry (default 1 hour). Looking up `pikachu` and then `25` creates two
`pokemon-details` entries, because sharing them would need a PokeAPI call on every miss to resolve the name.
The second lookup still reuses the cached evolution chain. The old `pokemon` cache is no longer used; any old
`pokemon::*` keys expire on their own.

Not found:

```json
{
  "title": "Pokemon not found",
  "status": 404,
  "detail": "Pokemon 'missingno' was not found",
  "instance": "/api/v1/pokemon/missingno"
}
```

### Update local data

```http
PUT   /api/v1/pokemon/{nameOrId}    Content-Type: application/json
PATCH /api/v1/pokemon/{nameOrId}    Content-Type: application/merge-patch+json (or application/json)
If-Match: "3"                       optional
```

Changes the proprietary data of a Pokémon **stored in the local database** (by a lookup or a sync). Only
PostgreSQL is used: a Pokémon that isn't stored yet gets `404`, even if it exists in PokeAPI. Look it up or
synchronize it first. Upstream (PokeAPI) fields can't be changed.

- **PUT** replaces everything. All three fields are required; an empty list clears that kind of data.
- **PATCH** follows JSON Merge Patch: a missing field is kept, `null` clears it, and a list replaces it.

```bash
curl -i -X PUT http://localhost:8080/api/v1/pokemon/pikachu \
  -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{
        "localizedNames": [ { "locale": "ja-jp", "name": "ピカチュウ" } ],
        "geoMetadata": [ { "kind": "region", "value": "kanto", "note": "Common in Viridian Forest" } ],
        "tags": [ "Mascot" ]
      }'

curl -X PATCH http://localhost:8080/api/v1/pokemon/25 \
  -H 'Content-Type: application/merge-patch+json' -d '{ "tags": null }'
```

Both return `200` with the updated `localData` (as in the detailed view) and its new `ETag`:

```json
{
  "localizedNames": [ { "locale": "ja-JP", "name": "ピカチュウ" } ],
  "geoMetadata": [ { "kind": "region", "value": "kanto", "note": "Common in Viridian Forest" } ],
  "tags": [ "mascot" ],
  "modifiedAt": "2026-10-18T10:00:00Z",
  "version": 1
}
```

**Validation rules**

| Field                        | Rules                                                                        |
|------------------------------|------------------------------------------------------------------------------|
| body                         | A JSON object. No duplicate keys, no trailing content, nesting depth up to 10, at most 64 KB (`LOCAL_DATA_MAX_REQUEST_SIZE`) |
| other fields                 | Upstream fields (`name`, `stats`, `types`, ...) are "read only"; anything else is "not a known field" |
| `localizedNames`             | At most 50, one per locale (ignoring case)                                   |
| `localizedNames[].locale`    | A well-formed BCP 47 language tag, stored in canonical form (`ja-jp` → `ja-JP`) |
| `localizedNames[].name`      | Not blank, up to 100 characters                                              |
| `geoMetadata`                | At most 20. The same kind and value may appear more than once                |
| `geoMetadata[].kind`         | `region`, `area` or `habitat` (any case)                                     |
| `geoMetadata[].value`        | Not blank, up to 100 characters                                              |
| `geoMetadata[].note`         | Optional, up to 1,000 characters; line breaks allowed                        |
| `tags`                       | At most 30, unique after normalisation                                       |
| `tags[]`                     | Up to 50 characters: letters, digits, `-` and `_`; stored in lowercase       |

All text is trimmed, and control characters are rejected. A `400` lists every problem, never echoing the
rejected values:

```json
{
  "title": "Invalid request",
  "status": 400,
  "detail": "The request body is invalid; see 'errors' for each problem",
  "errors": [
    { "field": "name", "message": "is read only" },
    { "field": "geoMetadata[0].kind", "message": "must be one of: region, area, habitat" },
    { "field": "tags", "message": "has the tag 'mascot' more than once" }
  ]
}
```

**Responses**

| Situation                                                   | HTTP status                  |
|-------------------------------------------------------------|------------------------------|
| Updated, or already as requested (version unchanged)        | `200 OK` + `ETag`            |
| Invalid body or field, invalid `nameOrId`, malformed `If-Match` | `400 Bad Request` (with `errors`) |
| Pokémon not stored locally                                  | `404 Not Found`              |
| Other methods, such as `DELETE`                             | `405 Method Not Allowed` + `Allow` |
| A change that conflicts with a database rule               | `409 Conflict`               |
| `If-Match` doesn't match the current version                | `412 Precondition Failed` (with `currentVersion`) |
| Body larger than 64 KB                                      | `413 Content Too Large`      |
| Body that isn't JSON                                        | `415 Unsupported Media Type` |
| Database unavailable                                        | `503 Service Unavailable`    |

**Versions and `If-Match`.** The `ETag` of the detailed view and of every update versions `localData` only,
not the PokeAPI part of the view, so a sync never makes it out of date. Send it as `If-Match` to make sure
nobody changed the data since you read it. `If-Match: *` matches any stored Pokémon, and weak tags (`W/"3"`)
never match. Without `If-Match`, the last write wins. `If-None-Match` isn't supported on the detailed view.

> Both operations need an access token of a user with the `USER` role (see [Auth](#auth-register-sign-in-refresh-sign-out)).
> Without one they answer `401` before the body is even read.

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

### Admin: synchronization

These endpoints need an access token of a user with the `ADMIN` role, such as the
[initial administrator](#initial-administrator): `401` without a valid token, `403` for a `USER`.

| Method and path                               | Purpose                                         | Responses |
|-----------------------------------------------|-------------------------------------------------|-----------|
| `POST /api/v1/admin/sync/runs`                | Start a full sync in the background             | `202` with a `Location` header, `409` if one is running |
| `GET /api/v1/admin/sync/runs/latest`          | Status of the most recent full sync             | `200`, `404` if none has run |
| `GET /api/v1/admin/sync/runs/{runId}`         | Status of one full sync                         | `200`, `400`, `404` |
| `POST /api/v1/admin/sync/pokemon/{nameOrId}`  | Synchronize one Pokémon now                     | `200`, `400`, `404`, `429`, `502`, `503` |

```bash
curl -i -X POST http://localhost:8080/api/v1/admin/sync/runs
curl http://localhost:8080/api/v1/admin/sync/runs/latest
```

```json
{
  "id": 1,
  "trigger": "API",
  "status": "COMPLETED_WITH_ERRORS",
  "startedAt": "2026-10-17T10:00:00Z",
  "finishedAt": "2026-10-17T10:04:12Z",
  "catalogueCount": 1302,
  "created": 1301,
  "updated": 0,
  "failed": 1,
  "failures": [
    { "name": "missingno", "reason": "Pokemon '9998' was not found" }
  ]
}
```

`status` is `RUNNING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED` or `INTERRUPTED`. `trigger` is `API` or
`SCHEDULED`. Starting a second full sync while one is running returns:

```json
{
  "type": "about:blank",
  "title": "Synchronization already running",
  "status": 409,
  "detail": "Synchronization run 1 is still running; follow it at /api/v1/admin/sync/runs/1",
  "instance": "/api/v1/admin/sync/runs",
  "runId": 1
}
```

Synchronizing one Pokémon returns whether its row was `CREATED` or `UPDATED`:

```bash
curl -X POST http://localhost:8080/api/v1/admin/sync/pokemon/pikachu
```

```json
{ "id": 25, "name": "pikachu", "outcome": "CREATED", "lastSyncedAt": "2026-10-17T10:05:00Z" }
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
- Protected operations carry the `bearerAuth` security scheme and document `401` and `403`. In Swagger UI,
  sign in with `POST /api/v1/auth/login`, click **Authorize**, paste the `accessToken`, and try them.

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

While the circuit is open, PokeAPI isn't called at all. Requests that aren't cached get a `503` straight
away. Cached Pokémon details and catalogue pages are still served normally.

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
| `SYNC_BATCH_SIZE`             | `app.sync.batch-size`             | `50` (catalogue entries per page, 1 to 100) |
| `SYNC_DELAY_BETWEEN_POKEMON`  | `app.sync.delay-between-pokemon`  | `100ms` |
| `SYNC_PAUSE_WHEN_REJECTED`    | `app.sync.pause-when-rejected`    | `5s` |
| `SYNC_MAX_CONSECUTIVE_PAUSES` | `app.sync.max-consecutive-pauses` | `12` |
| `SYNC_SCHEDULE_ENABLED`       | `app.sync.schedule.enabled`       | `false` |
| `SYNC_SCHEDULE_CRON`          | `app.sync.schedule.cron`          | `0 0 3 * * *` (Spring cron, daily at 03:00) |
| `LOCAL_DATA_MAX_REQUEST_SIZE` | `app.local-data.max-request-size` | `64KB` (largest PUT/PATCH body; larger gets `413`) |
| `JWT_SECRET`                  | `app.security.jwt-secret`         | **none**: required, at least 32 bytes; the app won't start without it |
| `ACCESS_TOKEN_TTL`            | `app.security.access-token-ttl`   | `15m` |
| `REFRESH_TOKEN_TTL`           | `app.security.refresh-token-ttl`  | `7d` |
| `MAX_SESSION_LIFETIME`        | `app.security.max-session-lifetime` | `30d` (from sign-in, however often it is refreshed) |
| `LOGIN_MAX_FAILURES` / `LOGIN_FAILURE_WINDOW` / `LOGIN_LOCKOUT` | `app.security.login-attempts.*` | `5` / `15m` / `15m` |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `app.security.initial-admin.*` | unset (no administrator is created) |

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
| `PokemonDetailsServiceTest` | Builds the view from Pokémon, species and chain; uses the linked species for alternate forms; normalizes input; stores new Pokémon once, ignoring concurrent duplicates; single-stage fallback; 404 and failures propagate (`@Mock`, `@Spy`, `@InjectMocks`, BDD `given`/`verify`) |
| `PokemonDetailsMapperTest`  | Stats order and total, artwork → sprite → `null` fallback, most recent English description and cleaning, evolution summaries (level, item, trade, friendship, unknown, joined), linear/branching/single-stage chains, current stage |
| `EvolutionChainLoaderTest`  | Fetches and maps a chain without a current stage, propagates client errors |
| `PokemonCatalogServiceTest` | Page building from the catalogue listing, offset and metadata calculation, partial last page, past-the-end, offset overflow, whole page failing with the original exception |
| `PokemonSummaryLoaderTest`  | Fetches by id, maps to a catalogue entry, propagates client errors |
| `PokemonControllerTest`| `@WebMvcTest` slice with `@MockitoBean` services: details and page JSON shape, `localData` and `ETag` on the detailed view, default and custom paging, 400 for invalid paging (parameterized), 400/404/429/502/503 mapping |
| `PokemonLocalDataControllerTest` | `@WebMvcTest` slice for PUT and PATCH: `200` with `ETag`, merge-patch and plain JSON, `If-Match` passed on, every invalid field in `errors`, malformed and missing bodies, invalid path and `If-Match`, `404`, `412` with `currentVersion`, `415`, `405` with `Allow`, `413` |
| `LocalDataRequestParserTest` | Normalisation (canonical locales, lowercase tags and kinds, blank notes), PUT needs every kind, PATCH keeps missing kinds and clears `null`, malformed bodies (empty, invalid, trailing content, not an object, duplicate keys, too deep), read-only and unknown fields, every rule with its field path, duplicates after normalisation, list sizes and types, repeated geo entries allowed |
| `IfMatchTest`          | Strong comparison, lists, `*`, weak tags never match, malformed headers |
| `LocalDataServiceTest` | Sorted reads; only what changed is written (rename in place, delete then insert); a no-op keeps the version; a partial update only touches what it sends; repeated geo entries; a stale `If-Match` writes nothing; lookup by id or name; not stored locally gives `404` without a lock |
| `LocalDataRequestSizeFilterTest` | A body without `Content-Length` fails once it goes over the limit; small bodies and other paths pass |
| `PasswordPolicyTest`   | Username characters and length, normalization, password length in characters (Unicode, spaces), password equal to the username, every problem listed |
| `LoginAttemptLimiterTest` | 5 failures lock a username until the lockout ends, the window slides, a success clears it, the number of tracked usernames is capped (with a movable clock) |
| `TokenServiceTest`     | Default lifetimes (15 minutes access, 7 days refresh) and configured ones, JWT claims (user, session, generation, roles), never outliving the session, unique refresh tokens, only their hash stored, nothing printed |
| `AuthServiceTest`      | Registration (normalized, hashed, `USER` only, duplicates and concurrent duplicates), sign-in (same error for unknown and wrong, limiter, session lifetime), refresh (rotation, next generation, reuse ends the session, expired/unknown/ended rejected, maximum lifetime), sign-out (ends the session, any token accepted, reuse) |
| `InitialAdminCreatorTest` | Creates the configured administrator, leaves an existing user alone, does nothing unless configured, refuses a password that breaks the policy |
| `AuthControllerTest`   | `@WebMvcTest` with the real security rules: `201` + `Location` (roles in the body ignored), `400` with `errors`, `409`, sign-in `200`/`401`/`429` + `Retry-After`, refresh and sign-out without an access token, `/user` with and without a token |
| `SecurityRulesTest`    | Real signed tokens against the real filter chain: every protected route gives `401` + `WWW-Authenticate` without a token and with a garbage, wrongly signed or expired one, before validation or any service; `403` without the role; `ADMIN` implies `USER`; ended sessions, older generations, deleted users and roles removed in the database are rejected; two `Authorization` headers; public routes ignore bad tokens; `405` unchanged; no cookies |
| `PokeApiClientTest`    | `RestClient` against `MockRestServiceServer`: deserialization (abilities, stats, artwork, species link), species and evolution chain parsing, catalogue listing, 404 → `PokemonNotFoundException`, 5xx → `PokeApiException` |
| `PokemonMapperTest`    | DTO → entity (types, stats, abilities, species) and catalogue entry mapping, type and ability ordering, hectograms → kilograms, null-safety |
| `PokemonTest`          | `copyUpstreamFrom` replaces upstream fields only and keeps identity and timestamps; tags are lowercased |
| `PokemonSyncWriterTest`| Creates new Pokémon, updates stored ones matched by PokeAPI id (including renames), retries as an update after a concurrent insert |
| `PokemonSyncServiceTest` | Single sync normalizes input and propagates 404; a full sync is recorded then run in the background; no run when one is already running; missing runs give `SyncRunNotFoundException` |
| `FullSyncRunnerTest`   | Walks every page and counts created/updated; records failures and carries on; pauses and retries when the circuit is open; fails after too many rejections or when the catalogue can't be read; never leaves a run `RUNNING` |
| `ScheduledSyncTest`    | Disabled by default, starts a `SCHEDULED` run when enabled, skips when a run is already running |
| `SyncRunRecoveryTest`  | Marks leftover `RUNNING` runs as interrupted on startup |
| `SyncControllerTest`   | `@WebMvcTest` slice (signed in as `ADMIN`): `202` with `Location`, `409` with `runId`, run and latest run (`200`/`404`), single sync `200`/`400`/`404` |

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
  `integration` profile. Before each test it clears the database (users included), all Redis caches,
  WireMock's request journal, the circuit breaker and the failed sign-in limiter. `bearerTokenFor(username,
  roles...)` gives a ready `Authorization` header for a new user, skipping the slow password hashing.
- `src/integrationTest/resources/application.properties` holds a throwaway JWT secret (and
  `src/test/resources/application.properties` one for the unit test slices).

The containers are static, JVM-wide singletons, so they start **once per test run**. Every Spring test
context reuses them, including contexts with different properties.

`application-integration.yml`, activated by the `integration` profile, shrinks the resilience settings so
the tests run fast and give the same result every time. Retries wait 10 ms, and the circuit opens after 4
recorded calls.

| Test                               | What it covers                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `PokemonApiIntegrationTest`        | Details lookup: image, stats (total 320), cleaned description and evolution chain with the current stage, empty `localData` with `ETag: "0"`, and no `localData` in the cached value; a repeat lookup makes no PokeAPI calls; Pichu and Pikachu share one chain request; an uncached view is refetched; one PostgreSQL row for `pikachu` and `25`; a cached view while the circuit is open; 404, 400, 502; a failing species call is retried and nothing is cached; actuator health |
| `PokemonCatalogIntegrationTest`    | Browsing a 3-Pokémon WireMock catalogue: entries and metadata, partial last page, a repeated page served from Redis with no PokeAPI calls, entries reused across page sizes, a cached page served while the circuit is open, `503` for an uncached page while open, past-the-end, invalid paging |
| `PokeApiResilienceIntegrationTest` | A 5xx is retried 3 times. A 404 is neither retried nor counted by the circuit breaker. Repeated failures open the circuit, and later calls get `503` without reaching PokeAPI (checked through `/actuator/circuitbreakers`). Cached data is still served while the circuit is open |
| `PokeApiRateLimiterIntegrationTest`| Uses its own context with a quota of 6 calls that doesn't refresh, enough for two detail lookups (3 calls each). The next upstream call gets `429` and never reaches WireMock, while cached Pokémon are still served |
| `OpenApiIntegrationTest`           | `/v3/api-docs` has the expected metadata, operations (including PUT and PATCH with their request schema, `If-Match` and 400/404/412/413/415), the `localData` schema and `ETag` header, the `bearerAuth` scheme on the protected operations with `401`/`403` (and not on public ones), the auth operations, documented error responses and paging parameters, and excludes actuator. Swagger UI is served |
| `PokemonRepositoryIntegrationTest` | `@DataJpaTest` against real PostgreSQL with Flyway: queries, type, stat and ability ordering, unique constraints, rows written before V2 still load, proprietary data constraints (one name per locale, unique lowercase tags, repeated geo entries allowed, note length), only one `RUNNING` sync run, the local data version starts at 0 and saving the entity never writes it back, users with roles, sessions and refresh tokens (unique lowercase usernames, cascades from the user) |
| `AuthIntegrationTest`              | Register, sign in, update local data, read `/user`, `403` on synchronization and `401` without a token; duplicates (and concurrent duplicates) give `409`; roles in the body are ignored; wrong username and wrong password give identical bodies; refresh retires the old pair and reuse ends the session; concurrent refreshes let one through; sign-out needs only the refresh token and ends only that session; repeated failures give `429`; deleted users and removed roles take effect at once; refresh tokens stored as hashes, passwords as PBKDF2, and no password or token in the logs |
| `AuthLifecycleIntegrationTest`     | With a movable clock: the access token expires after 15 minutes but can still be refreshed days later, and the refresh token after 7 days; refreshing keeps a session alive until its 30-day maximum lifetime; a lockout ends after 15 minutes; the initial administrator can sign in and run a full synchronization, and isn't changed when it exists |
| `LocalDataIntegrationTest`         | (Signed in as `ADMIN`.) PUT then GET shows the data while the PokeAPI part still comes from Redis; PATCH changes only what it sends, `null` clears, a no-op keeps the version; repeated geo entries; invalid requests change nothing; a Pokémon not stored locally gives `404` without calling PokeAPI, and can be updated after a lookup; full and single syncs keep the data and its version; two updates with the same `If-Match` give one `200` and one `412`; concurrent updates without `If-Match` alongside a sync all succeed |
| `PokemonSyncIntegrationTest`       | (Signed in as `ADMIN`.) A full sync of the 3-Pokémon WireMock catalogue stores every replicated field; a second run creates no duplicates; proprietary data survives full syncs, single syncs and lookups; a lookup-recorded row is completed, not duplicated; an upstream 404 gives `COMPLETED_WITH_ERRORS` with the failed name; a second start while running gives `409`; run status endpoints; single sync `200`/`404`/`400`. Runs are polled with Awaitility |

To add a PokeAPI stub, put a mapping in `wiremock/mappings/` and, if needed, a body file in
`wiremock/__files/`. The catch-all `not-found.json` mapping has lower priority, so any path without its
own stub returns 404. Details lookups also need `pokemon-species` and `evolution-chain` stubs (for example
`pikachu-species.json` and `evolution-chain-10.json`). The `broken` Pokémon links to a species stub that
always returns 503. The catalogue stubs (`catalogue-offset-*-limit-*.json`) match on the `offset` and
`limit` query parameters and describe a 3-Pokémon catalogue. The `integration` profile sets
`app.sync.batch-size: 2`, so a full sync reads two catalogue pages. Tests that need a different PokeAPI
response for a while (for example a slow or failing Pokémon) register a temporary stub with
`WireMockContainer#addStub` and remove it afterwards with `removeStub`.

## Database migrations

Flyway manages the schema. Hibernate runs with `ddl-auto: validate`, so it checks the entities against the
schema but never changes it.

- `V1__create_pokemon_tables.sql` creates the `pokemon` table and the `pokemon_type` table (ordered by slot).
- `V2__pokemon_sync.sql` adds the species and `last_synced_at` columns to `pokemon`, and the
  `pokemon_stat` and `pokemon_ability` tables (upstream data). It also adds the proprietary
  `pokemon_localized_name`, `pokemon_geo_metadata` and `pokemon_tag` tables, and the `sync_run` and
  `sync_run_failure` tables. Rows from V1 stay valid, and the next sync fills in their new fields.
- `V3__pokemon_local_data.sql` adds `local_data_version` (default 0) and `local_data_modified_at` to `pokemon`
  for [local data updates](#local-data-updates), and limits `pokemon_geo_metadata.note` to 1,000 characters.
  The `Pokemon` entity maps the two columns read only, so only `PokemonRepository#bumpLocalDataVersion`
  changes them.
- `V4__users_and_sessions.sql` adds `app_user` (lowercase unique usernames, password hashes), `app_user_role`,
  `auth_session` (one per sign-in, with its token generation, maximum lifetime and how it ended) and
  `auth_refresh_token` (SHA-256 hashes only; used tokens are kept to detect reuse). Sessions and tokens go
  with their user (`ON DELETE CASCADE`). Nothing cleans up old sessions yet.

The details lookup inserts a row the first time it sees a Pokémon, and skips it if one already exists, so it
never overwrites anything. The detailed view (description, evolution) is only cached in Redis, never stored.
See [Data synchronization](#data-synchronization) for the upstream/proprietary split.

**Adding a new kind of proprietary data:** create a table with a `pokemon_id BIGINT NOT NULL REFERENCES
pokemon (id) ON DELETE CASCADE` column in a new migration, plus an entity with a `@ManyToOne Pokemon` and a
repository. Don't map it from `Pokemon`, so the sync can never touch it. The upstream tables don't change.

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
  up. Raise it carefully and keep PokeAPI's fair use policy in mind. On `/api/v1/auth/login` it means too many
  failed sign-ins for that username; wait for `Retry-After`.
- **The app doesn't start: `app.security.jwt-secret`.** Set `JWT_SECRET` to at least 32 random bytes, for
  example `openssl rand -base64 48`. Changing it signs everyone out.
- **`401` on a route that used to work.** Updates and synchronization now need an access token, and access
  tokens last 15 minutes: refresh (within 7 days of the last sign-in or refresh), or sign in again.

## Next steps

This is a skeleton. Some natural extensions:

- A fallback that serves stale data when PokeAPI is unavailable (for example, a longer-lived "stale" cache)
- Retry-After headers on `429`/`503` responses, based on the rate limiter and circuit breaker state
- "Sign out everywhere", a list of a user's sessions, and user management (roles, disabling) through the API
- Cleaning up ended and expired sessions and refresh tokens (they are kept for now and grow with every
  sign-in and refresh)
- A failed sign-in limit shared between instances (for example in Redis)
- Conditional reads (`If-None-Match` and `304`) on the detailed view
- Distributed locking and recovery for full syncs across several application instances
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

**4. User Story 02: Detailed view (spec → plan → implementation)**

1. **Spec.** Running the command below created the branch `claude/feature/api-pokemon-details-01` and
   generated [`_specs/api-pokemon-details.md`](_specs/api-pokemon-details.md). Its open questions were
   answered in the spec. One more point, keeping the PostgreSQL storage, was confirmed in conversation.

   ```text
   /spec User Story 02 Detailed View Users must be able to access comprehensive data for a chosen
   Pokemon, specifically viewing its image, core statistics, narrative description, and evolutionary
   lineage.
   ```

2. **Plan.** An implementation plan was created from the spec and saved to
   [`_plans/api-pokemon-details.md`](_plans/api-pokemon-details.md). It covers the design decisions, the
   PokeAPI call sequence, caching and resilience approach, step-by-step tasks, affected files and risks.

3. **Implementation.** The plan was implemented as written. The result is the
   [Pokémon details](#get-pokémon-details-by-name-or-id) response of `GET /api/v1/pokemon/{nameOrId}`,
   together with its unit and integration tests.

**5. User Story 03: Data synchronization (spec → plan → implementation)**

1. **Spec.** Running the command below created the branch `claude/feature/pokemon-data-sync` and generated
   [`_specs/pokemon-data-sync.md`](_specs/pokemon-data-sync.md). All its open questions were answered with
   the suggested defaults.

   ```text
   /spec User Story 03 Data Synchronization Develop a mechanism to persist Pokemon data into a local
   relational store. This replication layer is intended to facilitate the addition of proprietary
   fields. Use cases include localized nomenclature, geographical metadata, or internal classification
   tags.
   ```

2. **Plan.** An implementation plan was created from the spec and saved to
   [`_plans/pokemon-data-sync.md`](_plans/pokemon-data-sync.md). It covers the decisions, the data model with
   the upstream/proprietary split, the sync components, the admin API, step-by-step tasks, affected files and
   risks.

3. **Implementation.** The plan was implemented with small deviations:
   - a `SyncRunStore` component holds the run bookkeeping
   - the new stat and ability tables are ordered by a `slot` column, like `pokemon_type`
   - the response DTOs follow the existing naming without a `Response` suffix: `SyncRunReport` and
     `SyncedPokemon`
   The result is the [Data synchronization](#data-synchronization) feature and its
   [admin endpoints](#admin-synchronization), together with their unit and integration tests.

**6. User Story 04: Local data modification (spec → plan → implementation)**

1. **Spec.** Running the command below created the branch `claude/feature/pokemon-local-update-01` and
   generated [`_specs/pokemon-local-update.md`](_specs/pokemon-local-update.md). Its open questions were
   answered in the spec: only proprietary data can be updated, with both PUT and PATCH on
   `/api/v1/pokemon/{nameOrId}`, `null` clears, optional `If-Match`, any well-formed locale, and repeated geo
   entries allowed. Two more points were decided in conversation: reads use only the existing
   `GET /api/v1/pokemon/{nameOrId}` (no separate read endpoint), and the version is stored on the `pokemon`
   table (no new table).

   ```text
   /spec User Story 04 Local Data Modification Enable update operations for any Pokemon currently stored
   within the local database. Ensure robust validation: provide 404 responses for missing records, 400
   status codes for malformed payloads, and incorporate further defensive logic as required.
   ```

2. **Plan.** An implementation plan was created from the spec and saved to
   [`_plans/pokemon-local-update.md`](_plans/pokemon-local-update.md). It covers the version columns and why
   they are read only for JPA, row locking, the diff-based update, the extended detailed view, validation and
   error handling, step-by-step tasks, affected files and risks.

3. **Implementation.** The plan was implemented with small deviations:
   - The body is parsed and validated by `LocalDataRequestParser`, with its own strict Jackson mapper, instead
     of a global Jackson customizer plus `@JsonAnySetter` and Bean Validation. The application's shared
     mapper is unchanged, which removes the plan's main risk, and wrong JSON types are reported per field.
   - A Pokémon that isn't stored locally raises its own `PokemonNotStoredLocallyException` (still `404`
     "Pokemon not found"), so the detail can say how to fix it.
   - The DTOs are named `LocalData`, `LocalizedName`, `GeoEntry` and `LocalDataRequest` (documentation only).
   The result is [Update local data](#update-local-data) and the `localData` field of the detailed view,
   together with their unit and integration tests.

**7. User Story 05: Authentication and protected routes (spec → plan → implementation)**

1. **Spec.** Running the command below created the branch `claude/feature/api-user-auth-protected-routes`
   and generated [`_specs/api-user-auth-protected-routes.md`](_specs/api-user-auth-protected-routes.md). Its
   open questions were answered in the spec: two roles, open registration, usernames, the password policy, and
   no session cleanup. Several points were added in conversation: refresh tokens and sign-out, both as public
   routes that take the refresh token; 15-minute access tokens and 7-day refresh tokens, both
   configurable; and
   `/api/v1/auth/user` as the current-user route.

   ```text
   /spec Implement an auxiliary API for user registration, authentication, and the management of protected
   versus public routes. The protected routes are: GET /api/v1/admin/sync/runs/{runId},
   GET /api/v1/admin/sync/runs/latest, POST /api/v1/admin/sync/runs, POST /api/v1/admin/sync/pokemon/{nameOrId},
   PATCH /api/v1/pokemon/{nameOrId}, PUT /api/v1/pokemon/{nameOrId}
   ```

2. **Plan.** An implementation plan was created from the spec and saved to
   [`_plans/api-user-auth-protected-routes.md`](_plans/api-user-auth-protected-routes.md). It covers the
   decisions, the data model, token and session design, the security filter chain, step-by-step tasks,
   affected files and risks.

3. **Implementation.** The plan was implemented with small deviations:
   - Entities and repositories live in the existing `domain/` and `repository/` packages, as the rest of the
     project does; `auth/` holds the services.
   - Registration and sign-in input is checked by `PasswordPolicy` and reported through
     `InvalidRequestException` (the `errors` list of US04) instead of Bean Validation.
   - One `ProblemDetailsSecurityHandler` writes both `401` and `403`, with its own `WWW-Authenticate` header,
     so the token library's error text (which can mention token details) never reaches a response.
   - The tolerated clock skew is 30 seconds instead of the library default of 60, to suit 15-minute tokens.
   - Refresh and sign-out accept tokens up to 4,096 characters, so an access token sent to sign-out by
     mistake gets `204` like any other unknown token.
   The result is the [Authentication](#authentication) feature and the
   [auth API](#auth-register-sign-in-refresh-sign-out), together with their unit and integration tests.

All generated code was reviewed. The full test suite (unit and Testcontainers integration tests)
passes, and the app was smoke-tested against the real PokeAPI using Docker Compose.
