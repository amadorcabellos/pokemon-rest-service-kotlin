# Plan: Synchronize Pokemon Data Into a Local Relational Store

- Spec: `_specs/pokemon-data-sync.md`
- Branch: `claude/feature/pokemon-data-sync`
- Saved copy: `_plans/pokemon-data-sync.md` (step 0)

## Context

User Story 03. Today the service only stores a basic Pokemon row (`pokemon` + `pokemon_type`) the first time
the lookup sees it. Everything else comes from PokeAPI and Redis. We want a deliberate replication of the
PokeAPI catalogue into PostgreSQL, kept up to date by a sync, with a clear home for proprietary data
(localized names, geographical metadata, classification tags) that a sync never touches. All open questions
were answered with the suggested defaults:

| Topic | Decision |
|---|---|
| Triggering | On demand via an admin API, plus an optional scheduled sync, **disabled by default**. No sync on startup. |
| Proprietary data management | **Storage only.** No endpoints to manage it in this story. |
| Exposing proprietary data | Not in the public responses. |
| Localized names | Purely proprietary; the sync leaves them empty (no seeding from PokeAPI species names). |
| Geographical metadata | Kind (region, area or habitat), value and an optional note. |
| Tags | Free text, normalised to lowercase, unique per Pokemon. |
| Replication scope | Only the fields in the spec (no descriptions or evolution chains). |
| Pokemon removed upstream | Kept as they are. |
| Admin security | Separated by path only (`/api/v1/admin/**`). |

## Design

### Data model (`V2__pokemon_sync.sql`)

**Upstream data** (owned by the `Pokemon` aggregate, written only by the sync and the lookup):
- `pokemon`: add `species_id INTEGER`, `species_name VARCHAR(100)`, `last_synced_at TIMESTAMPTZ` (all
  nullable, so V1 rows stay valid; `last_synced_at` stays null until a sync touches the row).
- `pokemon_stat (pokemon_id FK CASCADE, position, stat_name, base_value)`, PK `(pokemon_id, position)`.
- `pokemon_ability (pokemon_id FK CASCADE, position, ability_name, hidden)`, PK `(pokemon_id, position)`.

**Proprietary data** (separate entities, **not mapped on `Pokemon`**, so saving a `Pokemon` can never cascade
into them):
- `pokemon_localized_name (id, pokemon_id FK CASCADE, locale VARCHAR(35), name, created_at, updated_at)`,
  unique `(pokemon_id, locale)`.
- `pokemon_geo_metadata (id, pokemon_id FK CASCADE, kind VARCHAR(20) CHECK IN ('REGION','AREA','HABITAT'),
  value VARCHAR(100), note TEXT, created_at, updated_at)`.
- `pokemon_tag (id, pokemon_id FK CASCADE, tag VARCHAR(50) CHECK (tag = lower(tag)), created_at)`, unique
  `(pokemon_id, tag)`.

New kinds of proprietary data later are just new tables keyed by `pokemon_id`; upstream tables don't change.

**Sync runs:**
- `sync_run (id, trigger VARCHAR(20) [API|SCHEDULED], status VARCHAR(30), started_at, finished_at,
  catalogue_count, created_count, updated_count, failed_count)`.
- `CREATE UNIQUE INDEX sync_run_one_running ON sync_run (status) WHERE status = 'RUNNING'`: the database
  enforces "only one full sync at a time", atomically, with no in-memory lock.
- `sync_run_failure (id, sync_run_id FK CASCADE, pokemon_name, reason VARCHAR(500))`.

### Domain changes (`domain/`)

- `Pokemon`: add `speciesId`, `speciesName`, `lastSyncedAt`, plus `@ElementCollection` + `@OrderColumn(name =
  "position")` lists of new embeddables `PokemonStat(name, baseValue)` and `PokemonAbility(name, hidden)`,
  following the existing `types` mapping. Add:
  - `copyUpstreamFrom(Pokemon source)`: overwrites name, height, weight, base experience, sprite, types,
    stats, abilities and species. Never touches `id`, `createdAt` or anything proprietary.
  - `markSynced(Instant)`.
- New entities `PokemonLocalizedName`, `PokemonGeoMetadata` (+ `GeoKind` enum), `PokemonTag` (lowercases in
  its constructor), each with `@ManyToOne(fetch = LAZY, optional = false) Pokemon`. Repositories for each in
  `repository/` (used by tests now, by the management story later).
- `SyncRun` entity (+ `SyncRunStatus`: RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, INTERRUPTED;
  `SyncTrigger`: API, SCHEDULED) with a `@OneToMany(cascade = ALL)` list of `SyncRunFailure`.
  `SyncRunRepository` with `findFirstByOrderByStartedAtDesc()`, `findByStatus(RUNNING)` and a bulk
  `markRunningAs(INTERRUPTED, now)` update.

### Mapping

`PokemonMapper#toEntity` also fills stats (PokeAPI order), abilities (sorted by slot, like `toSummary`), and
species id/name (id via `PokeApiUrls.idFrom`). The lookup's `recordIfNew` in `PokemonDetailsService` keeps
working unchanged (insert-if-missing only, so it never overwrites anything) and now stores the richer row for
free, since it's the same PokeAPI response. Its `last_synced_at` stays null.

### Sync components (new package `com.ballastlane.poke.sync`)

- **`PokemonSyncWriter`** (`@Transactional`, one transaction per Pokemon): `upsert(PokeApiPokemon)` finds by
  PokeAPI id; updates via `copyUpstreamFrom` + `markSynced` or inserts a new row; returns `CREATED` or
  `UPDATED`. Matching by id handles renames (the name column is just updated).
- **`PokemonSyncService`**
  - `syncOne(nameOrId)`: normalises input like the lookup, calls `PokeApiClient.fetchPokemon` **outside** any
    transaction (same rule as `PokemonDetailsService`), then `writer.upsert`. On
    `DataIntegrityViolationException` (a concurrent insert by the lookup or the full sync), retries the upsert
    once, which then takes the update path. Returns `SyncedPokemon(id, name, outcome, lastSyncedAt)`.
    404/429/502/503 surface through the existing `GlobalExceptionHandler`.
  - `startFullSync(trigger)`: inserts a RUNNING `SyncRun` (in its own transaction); a unique-index violation
    becomes `SyncAlreadyRunningException(runningRunId)`. Then submits `FullSyncRunner.run(runId)` to the
    existing `applicationTaskExecutor` (virtual threads) and returns the run.
  - `getRun(id)` / `getLatestRun()`: throw `SyncRunNotFoundException` when missing.
- **`FullSyncRunner`**: walks `fetchPokemonPage(offset, batchSize)` until a page is empty or `offset >=
  count` (re-read from each page, so a growing catalogue is followed). For each entry, sequentially:
  `fetchPokemon(id)` → `writer.upsert`, then waits `delay-between-pokemon` so the sync uses at most about
  half of the shared 20 req/s rate limit. Counts are saved to `sync_run` after each page.
  - `PokemonNotFoundException`, `PokeApiException` (after retries), or any other per-Pokemon exception: record
    a `SyncRunFailure`, keep the old local row, continue.
  - `CallNotPermittedException` (circuit open) or `RequestNotPermitted` (rate limit): pause
    `pause-when-rejected`, then retry the same Pokemon or page. After `max-consecutive-pauses` in a row, stop
    the run as FAILED (already synchronized Pokemon are kept).
  - A catalogue page that fails after retries also ends the run as FAILED.
  - Final status: COMPLETED, or COMPLETED_WITH_ERRORS when `failed_count > 0`. Any unexpected exception in
    the runner itself marks the run FAILED (try/finally), so a run is never left RUNNING.
  - Logs start, per-page progress and the finish with counts; failures with name and reason.
- **`SyncRunRecovery`**: on `ApplicationReadyEvent`, marks any RUNNING runs as INTERRUPTED (finished now) and
  logs them. It covers restarts during a sync.
- **`ScheduledSync`**: `@ConditionalOnProperty("app.sync.schedule.enabled", havingValue = "true")`,
  `@Scheduled(cron = "${app.sync.schedule.cron}")` → `startFullSync(SCHEDULED)`; on
  `SyncAlreadyRunningException` it logs and skips. Add `@EnableScheduling` in a small `SyncConfig`.
- **`SyncProperties`** (`app.sync`, a validated record like `CatalogProperties`):
  - `batch-size` (default 50, 1..100)
  - `delay-between-pokemon` (default 100ms)
  - `pause-when-rejected` (default 5s)
  - `max-consecutive-pauses` (default 12, about a minute; covers the 30s circuit open time)
  - `schedule.enabled` (default false), `schedule.cron` (default `0 0 3 * * *`)
  Add the env-var overrides to `application.yml` like the existing settings.

Caches are not touched: the sync only writes PostgreSQL, and the public endpoints don't read it.

### Web layer

New `web/SyncController`, `@RequestMapping("/api/v1/admin/sync")`, `@Tag(name = "Admin: synchronization")`,
with OpenAPI annotations in the same style as `PokemonController`:

| Method and path | Result |
|---|---|
| `POST /runs` | 202, `SyncRunResponse`, `Location: /api/v1/admin/sync/runs/{id}`. 409 if one is running. |
| `GET /runs/latest` | 200 `SyncRunResponse`, or 404 if no run has ever happened. |
| `GET /runs/{id}` | 200 `SyncRunResponse`, or 404. |
| `POST /pokemon/{nameOrId}` | 200 `SyncedPokemonResponse`. Same `@Pattern` as the lookup. 400, 404, 429, 502, 503. |

- DTOs in `web/dto`: `SyncRunResponse(id, trigger, status, startedAt, finishedAt, catalogueCount, created,
  updated, failed, failures[{name, reason}])` and `SyncedPokemonResponse(id, name, outcome, lastSyncedAt)`.
- `GlobalExceptionHandler`: `SyncAlreadyRunningException` → 409 "Synchronization already running", with a
  `runId` property and the detail pointing to `/api/v1/admin/sync/runs/{runId}`. `SyncRunNotFoundException`
  → 404.
- `springdoc.paths-to-match: /api/**` already covers the admin path.

## Implementation steps

0. Copy this plan to `_plans/pokemon-data-sync.md`.
1. Migration `V2__pokemon_sync.sql` (all tables, constraints and the partial unique index above).
2. Domain: extend `Pokemon`; add the embeddables, proprietary entities, `SyncRun` and `SyncRunFailure`, and
   enums; add the repositories.
3. `PokemonMapper#toEntity`: stats, abilities and species.
4. Config: `SyncProperties`, `SyncConfig` (`@EnableScheduling`), and `app.sync.*` in `application.yml`.
5. Sync package: writer, service, runner, recovery and scheduler; exceptions in `exception/`.
6. Web: `SyncController`, DTOs, and the exception handlers.
7. Unit tests (`src/test/java`, Mockito):
   - `PokemonMapperTest`: `toEntity` with stats order, abilities sorted by slot, species id, missing
     sprite or base experience.
   - `PokemonTest` (plain unit): `copyUpstreamFrom` changes upstream fields only and keeps id and createdAt.
   - `PokemonSyncWriterTest`: create, update by PokeAPI id, rename.
   - `PokemonSyncServiceTest`:
     - `syncOne` normalises input, retries once on a duplicate key, propagates 404
     - `startFullSync` maps the unique violation to `SyncAlreadyRunningException` and submits the runner
   - `FullSyncRunnerTest`:
     - walks all pages and counts created and updated
     - continues after one failure, and ends as COMPLETED_WITH_ERRORS with the failure recorded
     - pauses and retries on `CallNotPermittedException`
     - fails after max pauses, and when a catalogue page fails
     - never leaves RUNNING
     Use zero delays via properties.
   - `ScheduledSyncTest`: skips when a sync is already running.
   - `SyncRunRecoveryTest`: marks RUNNING runs as INTERRUPTED.
   - `SyncControllerTest` (`@WebMvcTest`): 202 with `Location`, 409 with `runId`, latest and by-id 200 and
     404, single sync 200, 400 for an invalid name, 404.
8. Integration tests (`src/integrationTest/java`):
   - `application-integration.yml`: `app.sync.batch-size: 2`, zero `delay-between-pokemon`, short
     `pause-when-rejected`.
   - `AbstractApiIntegrationTest.resetState`: also clear `sync_run`. Proprietary tables are cleared by the
     cascade from `pokemon`.
   - Reuse the existing catalogue stubs (`catalogue-offset-*-limit-2`) and Pokemon stubs. For the partial
     failure case, register a temporary catalogue stub that lists a broken entry via WireMock's admin API
     (add a helper to `WireMockContainer` if it has none) and remove it after the test.
   - New `PokemonSyncIntegrationTest` (poll run status with Awaitility):
     - a full sync stores every catalogue Pokemon with stats, abilities, species and `last_synced_at`
     - a second run creates no rows and reports all as updated
     - proprietary rows inserted beforehand are unchanged after a full sync and after a single sync
     - a lookup-recorded Pokemon is completed, not duplicated
     - a partial failure gives COMPLETED_WITH_ERRORS with the failed name
     - a second start while running gives 409 (hold the first run with a WireMock fixed delay)
     - single sync gives 200, 404 and 400
     - the lookup doesn't overwrite proprietary data
   - `PokemonRepositoryIntegrationTest`: migrations V1 to V2 on existing rows. Insert a V1-shaped row, then
     check it loads with empty stats and abilities and a null `last_synced_at`.
   - `OpenApiIntegrationTest`: the admin sync operations and their responses are present.
9. README:
   - a "Data synchronization" architecture section and the local data model, including the
     upstream/proprietary split and how to add a new proprietary table
   - the admin API with examples
   - the `app.sync.*` settings in the configuration table
   - V2 in "Database migrations"
   - the testing tables
   - an AI-assisted development entry for User Story 03
10. Verify (below).

## Files

| Action | File (under `src/main/java/com/ballastlane/poke/` unless noted) |
|---|---|
| Add | `src/main/resources/db/migration/V2__pokemon_sync.sql` |
| Add | `domain/PokemonStat.java`, `domain/PokemonAbility.java`, `domain/PokemonLocalizedName.java`, `domain/PokemonGeoMetadata.java`, `domain/GeoKind.java`, `domain/PokemonTag.java`, `domain/SyncRun.java`, `domain/SyncRunFailure.java`, `domain/SyncRunStatus.java`, `domain/SyncTrigger.java` |
| Add | `repository/PokemonLocalizedNameRepository.java`, `repository/PokemonGeoMetadataRepository.java`, `repository/PokemonTagRepository.java`, `repository/SyncRunRepository.java` |
| Add | `sync/PokemonSyncWriter.java`, `sync/PokemonSyncService.java`, `sync/FullSyncRunner.java`, `sync/SyncRunRecovery.java`, `sync/ScheduledSync.java`, `sync/SyncOutcome.java`, `sync/SyncedPokemon.java` |
| Add | `config/SyncProperties.java`, `config/SyncConfig.java`, `exception/SyncAlreadyRunningException.java`, `exception/SyncRunNotFoundException.java` |
| Add | `web/SyncController.java`, `web/dto/SyncRunResponse.java`, `web/dto/SyncedPokemonResponse.java` |
| Modify | `domain/Pokemon.java`, `mapper/PokemonMapper.java`, `web/GlobalExceptionHandler.java`, `src/main/resources/application.yml`, `README.md` |
| Unchanged | `service/PokemonDetailsService.java` (behaviour unchanged, richer row via the mapper), `client/PokeApiClient.java`, caches, `V1` migration |
| Tests | New: the unit and integration tests listed above. Updated: `PokemonMapperTest`, `AbstractApiIntegrationTest`, `PokemonRepositoryIntegrationTest`, `OpenApiIntegrationTest`, `application-integration.yml`, possibly `WireMockContainer` |

## Verification

- `./gradlew build` runs unit and integration tests, and all of them pass, including existing ones.
- Smoke test with `docker compose up` against the real PokeAPI:
  - `POST /api/v1/admin/sync/pokemon/pikachu` gives 200 and a row with stats, abilities and species in psql
  - insert a tag and a localized name for Pikachu in psql, then sync it again; both are still there
  - `POST /api/v1/admin/sync/runs` gives 202. A second POST gives 409. `GET /runs/latest` shows progress, then
    COMPLETED with about 1,300 created.
  - restart the app during a run. The run shows as INTERRUPTED.
  - Swagger UI shows the "Admin: synchronization" endpoints.

## Risks

- **Full sync duration**: about 1,300 sequential calls with a 100ms delay take a few minutes. That's
  acceptable for a background job, and it can be tuned through `delay-between-pokemon`.
- **Shared rate limiter**: the sync and public traffic draw from the same `pokeapi` limiter. The delay keeps
  the sync at about half the quota. Lowering the delay can cause 429s for users during a sync.
- **Single instance assumed**: the partial unique index still prevents two RUNNING rows across instances,
  but `SyncRunRecovery` on one instance would mark another instance's live run as INTERRUPTED. This is
  documented as out of scope.
- **`Pokemon` gains two eager element collections**: loading a row now runs a few more queries. That's
  negligible, because nothing reads Pokemon rows on the hot path.
- **A rename that collides with another row's name** fails that Pokemon (unique name) and is recorded as a
  failure. This is very unlikely with PokeAPI data.
